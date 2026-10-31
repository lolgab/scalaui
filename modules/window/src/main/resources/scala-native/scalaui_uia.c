// Windows UI Automation provider, implemented in C because UIA calls providers from RPC threads that Scala Native knows
// nothing about. Scala hands over an immutable snapshot (scalaui_uia_set_nodes) and polls for presses; everything the
// COM objects touch lives in this file behind a critical section. Only the system headers are used.
#ifdef _WIN32
#include <windows.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <wctype.h>

#define S_OK_ 0
#define E_NOINTERFACE_ ((HRESULT)0x80004002L)
#define E_POINTER_ ((HRESULT)0x80004003L)
#define UIA_E_ELEMENTNOTAVAILABLE_ ((HRESULT)0x80040201L)

enum { K_SIMPLE, K_FRAGMENT, K_ROOT, K_INVOKE, K_TOGGLE, K_VALUE, K_RANGE, K_TEXT, K_COUNT };
enum { F_FOCUSED = 1, F_DISABLED = 2, F_INVOKE = 4, F_TOGGLE = 8, F_VALUE = 16, F_RANGE = 32, F_FOCUSABLE = 64, F_CHECKED = 128, F_TEXT = 256, F_EDITABLE = 512, F_MULTILINE = 1024 };

typedef struct { int ctype, flags; double x, y, w, h, rangeValue, rangeMax; WCHAR *name, *value; int caret, selStart, selEnd; } Node;
// edits requested by a screen reader: kind 0 replaces the text of `index`, kind 1 sets its selection to a..b
typedef struct { int index, kind, a, b; WCHAR *text; } Edit;
typedef struct { HWND hwnd; int count; Node *nodes; WCHAR *title; int pressQueue[64]; int pressHead, pressCount; Edit edits[32]; int editHead, editCount; } State;
typedef struct Iface { const void *const *vtbl; int node, kind; State *state; } Iface;

static CRITICAL_SECTION cs;
static volatile LONG csReady = 0;
static State *states[16];
static Iface **ifaces = NULL; static int ifaceCount = 0, ifaceCap = 0;

static void lock(void) {
    if (InterlockedCompareExchange(&csReady, 1, 0) == 0) InitializeCriticalSection(&cs);
    EnterCriticalSection(&cs);
}
static void unlock(void) { LeaveCriticalSection(&cs); }

// ---- dynamically loaded system functions
typedef LRESULT (WINAPI *ReturnProviderFn)(HWND, WPARAM, LPARAM, void *);
typedef HRESULT (WINAPI *HostProviderFn)(HWND, void **);
typedef HRESULT (WINAPI *RaiseEventFn)(void *, int);
typedef BOOL (WINAPI *ListeningFn)(void);
typedef WCHAR *(WINAPI *SysAllocFn)(const WCHAR *);
typedef WCHAR *(WINAPI *SysAllocLenFn)(const WCHAR *, UINT);
typedef HRESULT (WINAPI *NotSupportedFn)(IUnknown **);
typedef void *(WINAPI *SafeArrayCreateFn)(unsigned short, LONG, ULONG);
typedef HRESULT (WINAPI *SafeArrayPutFn)(void *, LONG *, void *);
static ReturnProviderFn pReturnProvider; static HostProviderFn pHostProvider; static RaiseEventFn pRaise; static ListeningFn pListening;
static SysAllocFn pSysAlloc; static SafeArrayCreateFn pSafeCreate; static SafeArrayPutFn pSafePut; static SysAllocLenFn pSysAllocLen; static NotSupportedFn pNotSupported;
static void loadFunctions(void) {
    if (pReturnProvider) return;
    HMODULE uia = LoadLibraryA("uiautomationcore.dll"), oa = LoadLibraryA("oleaut32.dll");
    pReturnProvider = (ReturnProviderFn)GetProcAddress(uia, "UiaReturnRawElementProvider");
    pHostProvider = (HostProviderFn)GetProcAddress(uia, "UiaHostProviderFromHwnd");
    pRaise = (RaiseEventFn)GetProcAddress(uia, "UiaRaiseAutomationEvent");
    pListening = (ListeningFn)GetProcAddress(uia, "UiaClientsAreListening");
    pSysAlloc = (SysAllocFn)GetProcAddress(oa, "SysAllocString");
    pSafeCreate = (SafeArrayCreateFn)GetProcAddress(oa, "SafeArrayCreateVector");
    pSafePut = (SafeArrayPutFn)GetProcAddress(oa, "SafeArrayPutElement");
    pSysAllocLen = (SysAllocLenFn)GetProcAddress(oa, "SysAllocStringLen");
    pNotSupported = (NotSupportedFn)GetProcAddress(uia, "UiaGetReservedNotSupportedValue");
}

// ---- GUIDs
static const GUID IID_Unknown_ = {0x00000000, 0x0000, 0x0000, {0xC0, 0, 0, 0, 0, 0, 0, 0x46}};
static const GUID IIDS[K_COUNT] = {
    {0xd6dd68d1, 0x86fd, 0x4332, {0x86, 0x66, 0x9a, 0xbe, 0xde, 0xa2, 0xd2, 0x4c}},
    {0xf7063da8, 0x8359, 0x439c, {0x92, 0x97, 0xbb, 0xc5, 0x29, 0x9a, 0x7d, 0x87}},
    {0x620ce2a5, 0xab8f, 0x40a9, {0x86, 0xcb, 0xde, 0x3c, 0x75, 0x59, 0x9b, 0x58}},
    {0x54fcb24b, 0xe18e, 0x47a2, {0xb4, 0xd3, 0xec, 0xcb, 0xe7, 0x75, 0x99, 0xa2}},
    {0x56d00bd0, 0xc4f4, 0x433c, {0xa8, 0x36, 0x1a, 0x52, 0xa5, 0x7e, 0x08, 0x92}},
    {0xc7935180, 0x6fb3, 0x4201, {0xb1, 0x74, 0x7d, 0xf7, 0x3a, 0xdb, 0xf6, 0x4a}},
    {0x36dc7aef, 0x33e6, 0x4691, {0xaf, 0xe1, 0x2b, 0xe7, 0x27, 0x4b, 0x3d, 0x33}},
    {0x3589c92c, 0x63f3, 0x4367, {0x99, 0xbb, 0xad, 0xa6, 0x53, 0xb7, 0x7c, 0xf2}}};   // ITextProvider
static const GUID IID_TextRange_ = {0x5347ad7b, 0xc355, 0x46f8, {0xaf, 0xf5, 0x90, 0x90, 0x33, 0x58, 0x2f, 0x63}};

static const void *const *VTABLES[K_COUNT];

// ---- instances (never freed: clients may hold pointers; at most nodes x kinds of them)
static Iface *get(State *s, int node, int kind) {
    for (int i = 0; i < ifaceCount; i++) if (ifaces[i]->state == s && ifaces[i]->node == node && ifaces[i]->kind == kind) return ifaces[i];
    if (ifaceCount == ifaceCap) { ifaceCap = ifaceCap ? ifaceCap * 2 : 64; ifaces = realloc(ifaces, ifaceCap * sizeof(Iface *)); }
    Iface *f = calloc(1, sizeof(Iface));
    f->vtbl = VTABLES[kind]; f->node = node; f->kind = kind; f->state = s;
    ifaces[ifaceCount++] = f;
    return f;
}
static Node *nodeOf(Iface *f) { return (f->node > 0 && f->node <= f->state->count) ? &f->state->nodes[f->node - 1] : NULL; }

static int supports(Iface *f, int kind) {
    if (kind == K_SIMPLE || kind == K_FRAGMENT) return 1;
    if (kind == K_ROOT) return f->node == 0;
    Node *n = nodeOf(f); if (!n) return 0;
    switch (kind) { case K_INVOKE: return n->flags & F_INVOKE; case K_TOGGLE: return n->flags & F_TOGGLE;
        case K_VALUE: return n->flags & F_VALUE; case K_RANGE: return n->flags & F_RANGE; case K_TEXT: return n->flags & F_TEXT; }
    return 0;
}

// ---- IUnknown
static HRESULT STDMETHODCALLTYPE qi(Iface *f, REFIID iid, void **out) {
    if (!out) return E_POINTER_;
    *out = NULL;
    if (IsEqualGUID(iid, &IID_Unknown_)) { *out = get(f->state, f->node, K_SIMPLE); return S_OK_; }
    lock();
    HRESULT hr = E_NOINTERFACE_;
    for (int k = 0; k < K_COUNT; k++)
        if (IsEqualGUID(iid, &IIDS[k]) && supports(f, k)) { *out = get(f->state, f->node, k); hr = S_OK_; break; }
    unlock();
    return hr;
}
static ULONG STDMETHODCALLTYPE addRef(Iface *f) { (void)f; return 2; }
static ULONG STDMETHODCALLTYPE releaseRef(Iface *f) { (void)f; return 1; }

// ---- helpers
static WCHAR *bstrOf(const WCHAR *s) { return pSysAlloc(s ? s : L""); }
static void variantInt(VARIANT *v, int i) { v->vt = VT_I4; v->lVal = i; }
static void variantBool(VARIANT *v, int b) { v->vt = VT_BOOL; v->boolVal = b ? VARIANT_TRUE : VARIANT_FALSE; }
static void variantStr(VARIANT *v, const WCHAR *s) { v->vt = VT_BSTR; v->bstrVal = bstrOf(s); }

// ---- IRawElementProviderSimple
static HRESULT STDMETHODCALLTYPE providerOptions(Iface *f, int *out) { (void)f; *out = 1; return S_OK_; } // ServerSideProvider

static HRESULT STDMETHODCALLTYPE patternProvider(Iface *f, int pattern, void **out) {
    int kind = pattern == 10000 ? K_INVOKE : pattern == 10015 ? K_TOGGLE : pattern == 10002 ? K_VALUE : pattern == 10003 ? K_RANGE : pattern == 10014 ? K_TEXT : -1;
    lock();
    *out = (kind >= 0 && f->node > 0 && supports(f, kind)) ? get(f->state, f->node, kind) : NULL;
    unlock();
    return S_OK_;
}

static HRESULT STDMETHODCALLTYPE propertyValue(Iface *f, int prop, VARIANT *v) {
    memset(v, 0, sizeof(VARIANT)); // VT_EMPTY = "not provided here"
    lock();
    Node *n = nodeOf(f);
    switch (prop) {
        case 30005: variantStr(v, f->node == 0 ? f->state->title : (n ? n->name : L"")); break;                       // Name
        case 30003: variantInt(v, f->node == 0 ? 50032 : (n ? n->ctype : 50026)); break;                                // ControlType
        case 30010: variantBool(v, !(n && (n->flags & F_DISABLED))); break;                                             // IsEnabled
        case 30009: variantBool(v, n && !(n->flags & F_DISABLED) && (n->flags & F_FOCUSABLE)); break;                   // IsKeyboardFocusable
        case 30008: variantBool(v, n && (n->flags & F_FOCUSED)); break;                                                 // HasKeyboardFocus
        case 30011: { WCHAR id[24]; if (f->node == 0) wcscpy(id, L"window"); else wsprintfW(id, L"n%d", f->node - 1); variantStr(v, id); break; }
        case 30012: variantStr(v, f->node == 0 ? L"ScalauiWindow" : L"ScalauiWidget"); break;                           // ClassName
        case 30016: case 30017: variantBool(v, 1); break;                                                               // IsControlElement/IsContentElement
        case 30024: variantStr(v, L"scalaui"); break;                                                                   // FrameworkId
        case 30020: if (f->node == 0) variantInt(v, (int)(INT_PTR)f->state->hwnd); break;                               // NativeWindowHandle
        default: break;
    }
    unlock();
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE hostProvider(Iface *f, void **out) {
    if (f->node == 0 && pHostProvider) return pHostProvider(f->state->hwnd, out);
    *out = NULL; return S_OK_;
}

// ---- IRawElementProviderFragment
static HRESULT STDMETHODCALLTYPE navigate(Iface *f, int dir, void **out) {
    lock();
    int n = f->state->count, id = f->node, target = -1;
    switch (dir) {
        case 0: if (id != 0) target = 0; break;                  // Parent
        case 1: if (id > 0 && id < n) target = id + 1; break;    // NextSibling
        case 2: if (id > 1) target = id - 1; break;              // PreviousSibling
        case 3: if (id == 0 && n > 0) target = 1; break;         // FirstChild
        case 4: if (id == 0 && n > 0) target = n; break;         // LastChild
    }
    *out = target >= 0 ? get(f->state, target, K_FRAGMENT) : NULL;
    unlock();
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE runtimeId(Iface *f, void **out) {
    if (f->node == 0) { *out = NULL; return S_OK_; }
    void *psa = pSafeCreate(VT_I4, 0, 2);
    LONG idx = 0; int v = 3; // UiaAppendRuntimeId
    pSafePut(psa, &idx, &v);
    idx = 1; v = f->node;
    pSafePut(psa, &idx, &v);
    *out = psa;
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE boundingRectangle(Iface *f, double *r) {
    lock();
    POINT pt = {0, 0};
    ClientToScreen(f->state->hwnd, &pt);
    HRESULT hr = S_OK_;
    if (f->node == 0) {
        RECT rc; GetClientRect(f->state->hwnd, &rc);
        r[0] = pt.x; r[1] = pt.y; r[2] = rc.right - rc.left; r[3] = rc.bottom - rc.top;
    } else {
        Node *n = nodeOf(f);
        if (n) { r[0] = pt.x + n->x; r[1] = pt.y + n->y; r[2] = n->w; r[3] = n->h; } else hr = UIA_E_ELEMENTNOTAVAILABLE_;
    }
    unlock();
    return hr;
}
static HRESULT STDMETHODCALLTYPE embeddedRoots(Iface *f, void **out) { (void)f; *out = NULL; return S_OK_; }
static HRESULT STDMETHODCALLTYPE setFocus(Iface *f) { (void)f; return S_OK_; }
static HRESULT STDMETHODCALLTYPE fragmentRoot(Iface *f, void **out) { *out = get(f->state, 0, K_ROOT); return S_OK_; }

// ---- IRawElementProviderFragmentRoot
static HRESULT STDMETHODCALLTYPE elementFromPoint(Iface *f, double x, double y, void **out) {
    lock();
    POINT pt = {0, 0}; ClientToScreen(f->state->hwnd, &pt);
    int hit = 0;
    for (int i = f->state->count - 1; i >= 0; i--) {
        Node *n = &f->state->nodes[i];
        if (x >= pt.x + n->x && x < pt.x + n->x + n->w && y >= pt.y + n->y && y < pt.y + n->y + n->h) { hit = i + 1; break; }
    }
    *out = get(f->state, hit, K_FRAGMENT);
    unlock();
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE focusedElement(Iface *f, void **out) {
    lock();
    *out = NULL;
    for (int i = 0; i < f->state->count; i++) if (f->state->nodes[i].flags & F_FOCUSED) { *out = get(f->state, i + 1, K_FRAGMENT); break; }
    unlock();
    return S_OK_;
}

// ---- patterns
static HRESULT STDMETHODCALLTYPE invoke(Iface *f) {
    lock();
    State *s = f->state;
    if (s->pressCount < 64) { s->pressQueue[(s->pressHead + s->pressCount) % 64] = f->node - 1; s->pressCount++; }
    unlock();
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE toggleState(Iface *f, int *out) { lock(); Node *n = nodeOf(f); *out = n && (n->flags & F_CHECKED) ? 1 : 0; unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE valueText(Iface *f, WCHAR **out) { lock(); Node *n = nodeOf(f); *out = bstrOf(n ? n->value : L""); unlock(); return S_OK_; }
static void queueEdit(State *s, int index, int kind, int a, int b, const WCHAR *text) {
    if (s->editCount >= 32) return;
    Edit *e = &s->edits[(s->editHead + s->editCount) % 32];
    e->index = index; e->kind = kind; e->a = a; e->b = b; e->text = text ? _wcsdup(text) : NULL;
    s->editCount++;
}
static HRESULT STDMETHODCALLTYPE setValueText(Iface *f, const WCHAR *t) {
    lock();
    Node *n = nodeOf(f);
    int ok = n && (n->flags & F_EDITABLE);
    if (ok) queueEdit(f->state, f->node - 1, 0, 0, 0, t ? t : L"");
    unlock();
    return ok ? S_OK_ : UIA_E_ELEMENTNOTAVAILABLE_;
}
static HRESULT STDMETHODCALLTYPE readOnly(Iface *f, BOOL *out) { lock(); Node *n = nodeOf(f); *out = (n && (n->flags & F_EDITABLE)) ? FALSE : TRUE; unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE setRange(Iface *f, double v) { (void)f; (void)v; return UIA_E_ELEMENTNOTAVAILABLE_; }
static HRESULT STDMETHODCALLTYPE rangeValue(Iface *f, double *out) { lock(); Node *n = nodeOf(f); *out = n ? n->rangeValue : 0; unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rangeMax(Iface *f, double *out) { lock(); Node *n = nodeOf(f); *out = n ? n->rangeMax : 1; unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rangeMin(Iface *f, double *out) { (void)f; *out = 0; return S_OK_; }
static HRESULT STDMETHODCALLTYPE rangeLarge(Iface *f, double *out) { (void)f; *out = 0.1; return S_OK_; }
static HRESULT STDMETHODCALLTYPE rangeSmall(Iface *f, double *out) { (void)f; *out = 0.01; return S_OK_; }
static HRESULT STDMETHODCALLTYPE toggle(Iface *f) { return invoke(f); }


// ---- ITextProvider / ITextRangeProvider (offsets are UTF-16 units of the node's value, the same unit UIA uses)
typedef struct Range { const void *const *vtbl; LONG refs; State *state; int node, start, end; } Range;
static const void *const *RANGE_VTBL;
static Range *newRange(State *s, int node, int start, int end) {
    Range *r = calloc(1, sizeof(Range));
    r->vtbl = RANGE_VTBL; r->refs = 1; r->state = s; r->node = node; r->start = start; r->end = end;
    return r;
}
static const WCHAR *textOf(Range *r, int *len) {
    Node *n = (r->node > 0 && r->node <= r->state->count) ? &r->state->nodes[r->node - 1] : NULL;
    const WCHAR *t = n && n->value ? n->value : L"";
    *len = (int)wcslen(t);
    return t;
}
static int isLow(WCHAR c) { return c >= 0xDC00 && c <= 0xDFFF; }
static int isWordCh(WCHAR c) { return iswalnum(c) || c == L'_' || (c >= 0xD800 && c <= 0xDFFF); }
// TextUnit: 0 character, 1 format, 2 word, 3 line, 4 paragraph, 5 page, 6 document
static int unitStart(const WCHAR *t, int len, int pos, int unit) {
    if (pos > len) pos = len;
    switch (unit) {
        case 0: while (pos > 0 && pos < len && isLow(t[pos])) pos--; return pos;
        case 2: { if (pos >= len) return len; if (t[pos] == L'\n') return pos;
            int cls = isWordCh(t[pos]); int p = pos;
            if (iswspace(t[pos]) && t[pos] != L'\n') { while (p > 0 && iswspace(t[p - 1]) && t[p - 1] != L'\n') p--; cls = 2; }
            else while (p > 0 && t[p - 1] != L'\n' && !iswspace(t[p - 1]) && isWordCh(t[p - 1]) == cls) p--;
            return p; }
        case 3: case 4: { int p = pos; while (p > 0 && t[p - 1] != L'\n') p--; return p; }
        default: return 0;
    }
}
static int unitEnd(const WCHAR *t, int len, int pos, int unit) {
    if (pos >= len) return len;
    switch (unit) {
        case 0: return (pos + 1 < len && isLow(t[pos + 1])) ? pos + 2 : pos + 1;
        case 2: { if (t[pos] == L'\n') return pos + 1;
            int p = pos; int ws = iswspace(t[p]);
            if (!ws) { int cls = isWordCh(t[p]); if (cls) while (p < len && isWordCh(t[p]) && !iswspace(t[p])) p++; else p++; }
            while (p < len && iswspace(t[p]) && t[p] != L'\n') p++;
            return p > pos ? p : pos + 1; }
        case 3: case 4: { int p = pos; while (p < len && t[p] != L'\n') p++; return p < len ? p + 1 : len; }
        default: return len;
    }
}
static HRESULT STDMETHODCALLTYPE rqi(Range *r, REFIID iid, void **out) {
    if (!out) return E_POINTER_;
    if (IsEqualGUID(iid, &IID_Unknown_) || IsEqualGUID(iid, &IID_TextRange_)) { *out = r; InterlockedIncrement(&r->refs); return S_OK_; }
    *out = NULL; return E_NOINTERFACE_;
}
static ULONG STDMETHODCALLTYPE rAddRef(Range *r) { return InterlockedIncrement(&r->refs); }
static ULONG STDMETHODCALLTYPE rRelease(Range *r) { LONG n = InterlockedDecrement(&r->refs); if (n == 0) free(r); return n; }
static HRESULT STDMETHODCALLTYPE rClone(Range *r, Range **out) { *out = newRange(r->state, r->node, r->start, r->end); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rCompare(Range *r, Range *o, BOOL *out) { *out = o && o->state == r->state && o->node == r->node && o->start == r->start && o->end == r->end; return S_OK_; }
static HRESULT STDMETHODCALLTYPE rCompareEndpoints(Range *r, int e1, Range *o, int e2, int *out) {
    int a = e1 == 0 ? r->start : r->end, b = e2 == 0 ? o->start : o->end;
    *out = a < b ? -1 : a > b ? 1 : 0; return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rExpand(Range *r, int unit) {
    lock(); int len; const WCHAR *t = textOf(r, &len);
    int s = unitStart(t, len, r->start, unit), e = unitEnd(t, len, s, unit);
    while (e < r->end && e < len) e = unitEnd(t, len, e, unit);
    r->start = s; r->end = e; unlock(); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rFindAttribute(Range *r, int id, VARIANT v, BOOL back, Range **out) { (void)r; (void)id; (void)v; (void)back; *out = NULL; return S_OK_; }
static HRESULT STDMETHODCALLTYPE rFindText(Range *r, WCHAR *text, BOOL back, BOOL ignoreCase, Range **out) {
    *out = NULL;
    lock(); int len; const WCHAR *t = textOf(r, &len); int m = text ? (int)wcslen(text) : 0;
    if (m > 0) {
        int from = r->start, to = r->end - m;
        for (int k = 0; k <= to - from; k++) {
            int i = back ? to - k : from + k;
            if ((ignoreCase ? _wcsnicmp(t + i, text, m) : wcsncmp(t + i, text, m)) == 0) { *out = newRange(r->state, r->node, i, i + m); break; }
        }
    }
    unlock(); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rGetAttribute(Range *r, int id, VARIANT *v) {
    (void)r; (void)id; memset(v, 0, sizeof(VARIANT));
    IUnknown *ns = NULL;
    if (pNotSupported && pNotSupported(&ns) == S_OK_) { v->vt = VT_UNKNOWN; v->punkVal = ns; }
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rBoundingRects(Range *r, void **out) {
    // character boxes are not published on Windows: report the field's rectangle (a rectangle is 4 doubles: x, y, w, h)
    void *psa = pSafeCreate(VT_R8, 0, 4);
    lock();
    POINT pt = {0, 0}; ClientToScreen(r->state->hwnd, &pt);
    Node *n = (r->node > 0 && r->node <= r->state->count) ? &r->state->nodes[r->node - 1] : NULL;
    double rc[4] = { n ? pt.x + n->x : 0, n ? pt.y + n->y : 0, n ? n->w : 0, n ? n->h : 0 };
    unlock();
    for (LONG i = 0; i < 4; i++) pSafePut(psa, &i, &rc[i]);
    *out = psa; return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rEnclosing(Range *r, void **out) { lock(); *out = get(r->state, r->node, K_SIMPLE); unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rGetText(Range *r, int max, WCHAR **out) {
    lock(); int len; const WCHAR *t = textOf(r, &len);
    int s = r->start < len ? r->start : len, e = r->end < len ? r->end : len; if (e < s) e = s;
    int n = e - s; if (max >= 0 && n > max) n = max;
    *out = pSysAllocLen(t + s, (UINT)n);
    unlock(); return S_OK_;
}
// moves `pos` by `count` units; returns the number of units actually moved
static int stepUnits(const WCHAR *t, int len, int *pos, int unit, int count) {
    int moved = 0;
    while (count > 0 && *pos < len) { *pos = unitEnd(t, len, *pos, unit); count--; moved++; }
    while (count < 0 && *pos > 0) { *pos = unitStart(t, len, *pos - 1, unit); count++; moved--; }
    return moved;
}
static HRESULT STDMETHODCALLTYPE rMove(Range *r, int unit, int count, int *moved) {
    lock(); int len; const WCHAR *t = textOf(r, &len);
    int s = unitStart(t, len, r->start, unit);
    *moved = stepUnits(t, len, &s, unit, count);
    r->start = s; r->end = unitEnd(t, len, s, unit); unlock(); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rMoveEndpointByUnit(Range *r, int ep, int unit, int count, int *moved) {
    lock(); int len; const WCHAR *t = textOf(r, &len);
    int *p = ep == 0 ? &r->start : &r->end;
    *moved = stepUnits(t, len, p, unit, count);
    if (r->start > r->end) { if (ep == 0) r->end = r->start; else r->start = r->end; }
    unlock(); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rMoveEndpointByRange(Range *r, int ep, Range *o, int oep) {
    int v = oep == 0 ? o->start : o->end;
    if (ep == 0) { r->start = v; if (r->end < v) r->end = v; } else { r->end = v; if (r->start > v) r->start = v; }
    return S_OK_;
}
static HRESULT STDMETHODCALLTYPE rSelect(Range *r) { lock(); queueEdit(r->state, r->node - 1, 1, r->start, r->end, NULL); unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rAddToSelection(Range *r) { return rSelect(r); }
static HRESULT STDMETHODCALLTYPE rRemoveFromSelection(Range *r) { lock(); queueEdit(r->state, r->node - 1, 1, r->start, r->start, NULL); unlock(); return S_OK_; }
static HRESULT STDMETHODCALLTYPE rScroll(Range *r, BOOL top) { (void)r; (void)top; return S_OK_; }
static HRESULT STDMETHODCALLTYPE rChildren(Range *r, void **out) { (void)r; *out = pSafeCreate(VT_UNKNOWN, 0, 0); return S_OK_; }

static void *rangeArray(Range *r) {   // SAFEARRAY(IUnknown*) holding one range
    void *psa = pSafeCreate(VT_UNKNOWN, 0, 1);
    LONG i = 0; pSafePut(psa, &i, r);   // SafeArrayPutElement AddRefs the interface
    return psa;
}
static HRESULT STDMETHODCALLTYPE tGetSelection(Iface *f, void **out) {
    lock(); Node *n = nodeOf(f);
    int a = n ? n->selStart : 0, b = n ? n->selEnd : 0, c = n ? n->caret : 0;
    if (a == b) a = b = c;   // a degenerate range marks the caret
    Range *r = newRange(f->state, f->node, a < b ? a : b, a < b ? b : a);
    unlock();
    *out = rangeArray(r); rRelease(r); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE tVisibleRanges(Iface *f, void **out) {
    lock(); Node *n = nodeOf(f); Range *r = newRange(f->state, f->node, 0, n && n->value ? (int)wcslen(n->value) : 0); unlock();
    *out = rangeArray(r); rRelease(r); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE tDocumentRange(Iface *f, Range **out) {
    lock(); Node *n = nodeOf(f); *out = newRange(f->state, f->node, 0, n && n->value ? (int)wcslen(n->value) : 0); unlock(); return S_OK_;
}
static HRESULT STDMETHODCALLTYPE tRangeFromChild(Iface *f, void *child, Range **out) { (void)child; return tDocumentRange(f, out); }
typedef struct { double x, y; } UiaPoint_;
static HRESULT STDMETHODCALLTYPE tRangeFromPoint(Iface *f, UiaPoint_ pt, Range **out) { (void)pt; Range *r; tDocumentRange(f, &r); r->end = r->start; *out = r; return S_OK_; }
static HRESULT STDMETHODCALLTYPE tSupportedSelection(Iface *f, int *out) { (void)f; *out = 1; return S_OK_; } // SupportedTextSelection_Single

static void buildTables(void) {
    static const void *simple[] = {qi, addRef, releaseRef, providerOptions, patternProvider, propertyValue, hostProvider};
    static const void *fragment[] = {qi, addRef, releaseRef, navigate, runtimeId, boundingRectangle, embeddedRoots, setFocus, fragmentRoot};
    static const void *root[] = {qi, addRef, releaseRef, elementFromPoint, focusedElement};
    static const void *inv[] = {qi, addRef, releaseRef, invoke};
    static const void *tog[] = {qi, addRef, releaseRef, toggle, toggleState};
    static const void *val[] = {qi, addRef, releaseRef, setValueText, valueText, readOnly};
    static const void *rng[] = {qi, addRef, releaseRef, setRange, rangeValue, readOnly, rangeMax, rangeMin, rangeLarge, rangeSmall};
    static const void *txt[] = {qi, addRef, releaseRef, tGetSelection, tVisibleRanges, tRangeFromChild, tRangeFromPoint, tDocumentRange, tSupportedSelection};
    static const void *rngv[] = {rqi, rAddRef, rRelease, rClone, rCompare, rCompareEndpoints, rExpand, rFindAttribute, rFindText, rGetAttribute,
        rBoundingRects, rEnclosing, rGetText, rMove, rMoveEndpointByUnit, rMoveEndpointByRange, rSelect, rAddToSelection, rRemoveFromSelection, rScroll, rChildren};
    VTABLES[K_TEXT] = txt; RANGE_VTBL = rngv;
    VTABLES[K_SIMPLE] = simple; VTABLES[K_FRAGMENT] = fragment; VTABLES[K_ROOT] = root; VTABLES[K_INVOKE] = inv;
    VTABLES[K_TOGGLE] = tog; VTABLES[K_VALUE] = val; VTABLES[K_RANGE] = rng;
}

static State *stateFor(HWND hwnd, int create) {
    for (int i = 0; i < 16; i++) if (states[i] && states[i]->hwnd == hwnd) return states[i];
    if (!create) return NULL;
    for (int i = 0; i < 16; i++) if (!states[i]) { states[i] = calloc(1, sizeof(State)); states[i]->hwnd = hwnd; states[i]->title = _wcsdup(L""); return states[i]; }
    return NULL;
}

// ---- entry points for Scala
// Replaces the snapshot. Arrays hold `n` entries; `bounds` is 4 doubles (client pixels) and `numbers` 2 doubles (range value, max) per node;
// `textInfo` is 3 ints per node: caret, selection start, selection end (UTF-16 units of the value).
void scalaui_uia_set_nodes(HWND hwnd, int n, const int *ctypes, const int *flags, const double *bounds, const double *numbers,
                           const WCHAR **names, const WCHAR **values, const WCHAR *title, const int *textInfo) {
    loadFunctions();
    lock();
    if (!VTABLES[0]) buildTables();
    State *s = stateFor(hwnd, 1);
    if (s) {
        for (int i = 0; i < s->count; i++) { free(s->nodes[i].name); free(s->nodes[i].value); }
        free(s->nodes);
        s->nodes = calloc(n > 0 ? n : 1, sizeof(Node)); s->count = n;
        for (int i = 0; i < n; i++) {
            Node *x = &s->nodes[i];
            x->ctype = ctypes[i]; x->flags = flags[i];
            x->x = bounds[4 * i]; x->y = bounds[4 * i + 1]; x->w = bounds[4 * i + 2]; x->h = bounds[4 * i + 3];
            x->rangeValue = numbers[2 * i]; x->rangeMax = numbers[2 * i + 1];
            x->name = _wcsdup(names[i]); x->value = _wcsdup(values[i]);
            x->caret = textInfo[3 * i]; x->selStart = textInfo[3 * i + 1]; x->selEnd = textInfo[3 * i + 2];
        }
        free(s->title); s->title = _wcsdup(title);
    }
    unlock();
}

LRESULT scalaui_uia_root(HWND hwnd, WPARAM wp, LPARAM lp) {
    loadFunctions();
    lock();
    if (!VTABLES[0]) buildTables();
    State *s = stateFor(hwnd, 1);
    Iface *root = s ? get(s, 0, K_SIMPLE) : NULL;
    unlock();
    return root ? pReturnProvider(hwnd, wp, lp, root) : 0;
}

// Next element index activated by a screen reader (Invoke/Toggle), or -1.
int scalaui_uia_poll_press(HWND hwnd) {
    lock();
    State *s = stateFor(hwnd, 0);
    int r = -1;
    if (s && s->pressCount > 0) { r = s->pressQueue[s->pressHead]; s->pressHead = (s->pressHead + 1) % 64; s->pressCount--; }
    unlock();
    return r;
}

// Next edit a screen reader requested (ValuePattern.SetValue, TextRange.Select): returns the element index or -1. `kind` 0 = replace
// the text with `*text` (malloc'ed, release with scalaui_uia_free), 1 = select a..b.
int scalaui_uia_poll_edit(HWND hwnd, int *kind, int *a, int *b, WCHAR **text) {
    lock();
    State *s = stateFor(hwnd, 0);
    int r = -1;
    if (s && s->editCount > 0) {
        Edit *e = &s->edits[s->editHead];
        r = e->index; *kind = e->kind; *a = e->a; *b = e->b; *text = e->text;
        s->editHead = (s->editHead + 1) % 32; s->editCount--;
    }
    unlock();
    return r;
}
void scalaui_uia_free(void *p) { free(p); }

// Raises TextChanged (kind 0) or TextSelectionChanged (kind 1) for element `index`.
void scalaui_uia_text_event(HWND hwnd, int index, int kind) {
    loadFunctions();
    lock();
    State *s = stateFor(hwnd, 0);
    Iface *target = (s && index >= 0 && index < s->count) ? get(s, index + 1, K_SIMPLE) : NULL;
    unlock();
    if (target && pListening && pListening()) pRaise(target, kind == 0 ? 20015 : 20014); // UIA_Text_TextChangedEventId / TextSelectionChangedEventId
}

void scalaui_uia_focus_changed(HWND hwnd, int index) {
    loadFunctions();
    lock();
    State *s = stateFor(hwnd, 0);
    Iface *target = (s && index >= 0 && index < s->count) ? get(s, index + 1, K_SIMPLE) : NULL;
    unlock();
    if (target && pListening && pListening()) pRaise(target, 20005); // UIA_AutomationFocusChangedEventId
}
#endif
