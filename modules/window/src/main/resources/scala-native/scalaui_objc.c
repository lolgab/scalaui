#ifdef __APPLE__
// Autolink libobjc (LC_LINKER_OPTION, honoured by Apple's ld and by ld64.lld, so cross links too). The shim is compiled
// into every binary that has `window` on its classpath, even when no Scala code is reachable to bring in @link("objc");
// `#pragma comment(lib)` is not implemented for Mach-O by clang. libobjc is part of every macOS install.
__asm__(".linker_option \"-lobjc\"");
// Scala Native cannot pass or return structs by value, so the few Objective-C calls that do are
// wrapped here with pointer-based signatures. Only system headers; no third-party code.
#include <objc/message.h>
#include <objc/runtime.h>

// CoreGraphics' geometry types, declared here so the shim needs only the Objective-C runtime headers (no SDK
// frameworks: they are dlopen'ed at run time). Same layout as CGFloat == double on every 64-bit Apple target.
typedef struct { double x, y; } CGPoint;
typedef struct { double width, height; } CGSize;
typedef struct { CGPoint origin; CGSize size; } CGRect;
static inline CGPoint CGPointMake(double x, double y) { CGPoint p = { x, y }; return p; }
static inline CGSize CGSizeMake(double w, double h) { CGSize s = { w, h }; return s; }
static inline CGRect CGRectMake(double x, double y, double w, double h) { CGRect r = { { x, y }, { w, h } }; return r; }

CGRect scalaui_call_ret_rect(id self, SEL sel) {
#if defined(__x86_64__)
    CGRect r;
    ((void (*)(CGRect *, id, SEL))objc_msgSend_stret)(&r, self, sel);
    return r;
#else
    return ((CGRect(*)(id, SEL))objc_msgSend)(self, sel);
#endif
}

void scalaui_ret_rect(id self, SEL sel, double *out) {
    CGRect r = scalaui_call_ret_rect(self, sel);
    out[0] = r.origin.x; out[1] = r.origin.y; out[2] = r.size.width; out[3] = r.size.height;
}

void scalaui_ret_point(id self, SEL sel, double *out) {
    CGPoint p = ((CGPoint(*)(id, SEL))objc_msgSend)(self, sel);
    out[0] = p.x; out[1] = p.y;
}

id scalaui_init_window(id self, SEL sel, const double *rect, unsigned long style, long backing, signed char defer) {
    CGRect r = CGRectMake(rect[0], rect[1], rect[2], rect[3]);
    return ((id(*)(id, SEL, CGRect, unsigned long, long, signed char))objc_msgSend)(self, sel, r, style, backing, defer);
}

// -[CAMetalLayer setDrawableSize:]
void scalaui_set_drawable_size(id self, SEL sel, double w, double h) {
    CGSize s = CGSizeMake(w, h);
    ((void (*)(id, SEL, CGSize))objc_msgSend)(self, sel, s);
}

typedef struct { double r, g, b, a; } ScaluiClearColor;
typedef struct { double x, y, w, h, zn, zf; } ScaluiViewport;

// MTLClearColorMake in -[MTLRenderPassColorAttachmentDescriptor setClearColor:]
void scalaui_set_clear_color(id self, SEL sel, double r, double g, double b, double a) {
    ScaluiClearColor c = { r, g, b, a };
    ((void (*)(id, SEL, ScaluiClearColor))objc_msgSend)(self, sel, c);
}

// -[MTLRenderCommandEncoder setViewport:]
void scalaui_set_viewport(id self, SEL sel, double w, double h) {
    ScaluiViewport v = { 0, 0, w, h, 0, 1 };
    ((void (*)(id, SEL, ScaluiViewport))objc_msgSend)(self, sel, v);
}

// -[MTLRenderCommandEncoder setScissorRect:]
typedef struct { unsigned long x, y, w, h; } ScaluiScissor;
void scalaui_set_scissor(id self, SEL sel, unsigned long x, unsigned long y, unsigned long w, unsigned long h) {
    ScaluiScissor r = { x, y, w, h };
    ((void (*)(id, SEL, ScaluiScissor))objc_msgSend)(self, sel, r);
}

// -[MTLTexture replaceRegion:mipmapLevel:withBytes:bytesPerRow:]
typedef struct { unsigned long x, y, z; } ScaluiOrigin;
typedef struct { unsigned long w, h, d; } ScaluiSize3;
typedef struct { ScaluiOrigin origin; ScaluiSize3 size; } ScaluiRegion;
void scalaui_replace_region(id self, SEL sel, unsigned long x, unsigned long y, unsigned long w, unsigned long h, const void *bytes, unsigned long bytesPerRow) {
    ScaluiRegion r = { { x, y, 0 }, { w, h, 1 } };
    ((void (*)(id, SEL, ScaluiRegion, unsigned long, const void *, unsigned long))objc_msgSend)(self, sel, r, 0, bytes, bytesPerRow);
}

// -[MTLTexture getBytes:bytesPerRow:fromRegion:mipmapLevel:] (tests read drawables back)
void scalaui_get_bytes(id self, SEL sel, void *bytes, unsigned long bytesPerRow, unsigned long w, unsigned long h) {
    ScaluiRegion r = { { 0, 0, 0 }, { w, h, 1 } };
    ((void (*)(id, SEL, void *, unsigned long, ScaluiRegion, unsigned long))objc_msgSend)(self, sel, bytes, bytesPerRow, r, 0);
}

// ---------------------------------------------------------------------------------------------------------------------
// ScaluiView: an NSView subclass created at runtime. It implements NSTextInputClient (input methods / dead keys / emoji
// picker), the NSDraggingDestination methods for file drops, cursor rects and backing-scale notifications. Everything it
// observes is queued per view and drained from Scala with scalaui_view_poll; no callbacks into Scala are needed.
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>

enum { SV_TEXT = 1, SV_COMPOSE = 2, SV_DROP = 3, SV_HOVER = 4, SV_HOVER_END = 5, SV_SCALE = 6, SV_ACCESS_PRESS = 7, SV_MENU = 8, SV_QUIT = 9, SV_ACCESS_SELECT = 10 };

typedef struct { int type; double a, b, c; char *text; } ScaluiViewEvent;
#define SV_QUEUE 256
typedef struct {
    ScaluiViewEvent q[SV_QUEUE];
    int head, count;
    char *marked;            // UTF-8 composition text
    unsigned long markedLen; // its length in UTF-16 units
    double imeRect[4];       // caret rectangle in view coordinates (AppKit, origin bottom-left)
    id cursor;
    int hasCursor;
    id access;               // NSArray of ScaluiAccessElement, retained
} ScaluiViewState;

static ptrdiff_t sv_state_offset = 0;
static ScaluiViewState *sv_state(id self) { return *(ScaluiViewState **)((char *)self + sv_state_offset); }

static void sv_push(id self, int type, double a, double b, double c, const char *text) {
    ScaluiViewState *st = sv_state(self);
    if (!st || st->count == SV_QUEUE) return;
    ScaluiViewEvent *e = &st->q[(st->head + st->count) % SV_QUEUE];
    e->type = type; e->a = a; e->b = b; e->c = c; e->text = text ? strdup(text) : NULL;
    st->count++;
}

// polls one event; the caller owns `out->text` and releases it with scalaui_free
int scalaui_view_poll(id self, ScaluiViewEvent *out) {
    ScaluiViewState *st = sv_state(self);
    if (!st || st->count == 0) return 0;
    *out = st->q[st->head];
    st->head = (st->head + 1) % SV_QUEUE; st->count--;
    return 1;
}
void scalaui_free(void *p) { free(p); }

static const char *sv_utf8(id s) { return ((const char *(*)(id, SEL))objc_msgSend)(s, sel_registerName("UTF8String")); }
static unsigned long sv_utf16_len(id s) { return ((unsigned long (*)(id, SEL))objc_msgSend)(s, sel_registerName("length")); }

// text arguments may be NSString or NSAttributedString
static id sv_plain(id s) {
    Class attr = objc_getClass("NSAttributedString");
    if (((signed char (*)(id, SEL, Class))objc_msgSend)(s, sel_registerName("isKindOfClass:"), attr))
        return ((id (*)(id, SEL))objc_msgSend)(s, sel_registerName("string"));
    return s;
}

static signed char sv_yes(id self, SEL _cmd) { (void)self; (void)_cmd; return 1; }
static signed char sv_no(id self, SEL _cmd) { (void)self; (void)_cmd; return 0; }
static void sv_noop1(id self, SEL _cmd, id a) { (void)self; (void)_cmd; (void)a; }

static void sv_clear_marked(id self) {
    ScaluiViewState *st = sv_state(self);
    free(st->marked); st->marked = NULL; st->markedLen = 0;
}

typedef struct { unsigned long location, length; } SVRange;

static void sv_insert_text(id self, SEL _cmd, id text, SVRange replacement) {
    (void)_cmd; (void)replacement;
    id s = sv_plain(text);
    int had = sv_state(self)->marked != NULL;
    sv_clear_marked(self);
    if (had) sv_push(self, SV_COMPOSE, 0, 0, 0, "");
    sv_push(self, SV_TEXT, 0, 0, 0, sv_utf8(s));
}

static void sv_set_marked(id self, SEL _cmd, id text, SVRange selected, SVRange replacement) {
    (void)_cmd; (void)replacement;
    id s = sv_plain(text);
    ScaluiViewState *st = sv_state(self);
    sv_clear_marked(self);
    const char *u = sv_utf8(s);
    if (u && u[0]) { st->marked = strdup(u); st->markedLen = sv_utf16_len(s); }
    sv_push(self, SV_COMPOSE, (double)selected.location, (double)selected.length, 0, u ? u : "");
}

static void sv_unmark(id self, SEL _cmd) {
    (void)_cmd;
    if (sv_state(self)->marked) { sv_clear_marked(self); sv_push(self, SV_COMPOSE, 0, 0, 0, ""); }
}

static signed char sv_has_marked(id self, SEL _cmd) { (void)_cmd; return sv_state(self)->marked != NULL; }
static SVRange sv_marked_range(id self, SEL _cmd) {
    (void)_cmd;
    ScaluiViewState *st = sv_state(self);
    SVRange r = st->marked ? (SVRange){ 0, st->markedLen } : (SVRange){ (unsigned long)-1 >> 1, 0 };
    return r;
}
static SVRange sv_selected_range(id self, SEL _cmd) { (void)self; (void)_cmd; return (SVRange){ 0, 0 }; }
static id sv_substring(id self, SEL _cmd, SVRange r, void *actual) { (void)self; (void)_cmd; (void)r; (void)actual; return NULL; }
static id sv_valid_attrs(id self, SEL _cmd) { (void)self; (void)_cmd; return ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSArray"), sel_registerName("array")); }
static unsigned long sv_char_index(id self, SEL _cmd, CGPoint p) { (void)self; (void)_cmd; (void)p; return (unsigned long)-1 >> 1; /* NSNotFound */ }
// native menu items target the first responder: the view turns them into queued events
static void sv_menu_action(id self, SEL _cmd, id sender) {
    (void)_cmd;
    long tag = ((long (*)(id, SEL))objc_msgSend)(sender, sel_registerName("tag"));
    sv_push(self, SV_MENU, (double)tag, 0, 0, NULL);
}
static void sv_quit_action(id self, SEL _cmd, id sender) { (void)_cmd; (void)sender; sv_push(self, SV_QUIT, 0, 0, 0, NULL); }
static void sv_do_command(id self, SEL _cmd, SEL command) { (void)self; (void)_cmd; (void)command; }

// candidate window anchor, in screen coordinates
static CGRect sv_first_rect(id self, SEL _cmd, SVRange r, void *actual) {
    (void)_cmd; (void)r; (void)actual;
    ScaluiViewState *st = sv_state(self);
    CGRect local = CGRectMake(st->imeRect[0], st->imeRect[1], st->imeRect[2], st->imeRect[3]);
    CGRect inWindow = ((CGRect (*)(id, SEL, CGRect, id))objc_msgSend)(self, sel_registerName("convertRect:toView:"), local, NULL);
    id window = ((id (*)(id, SEL))objc_msgSend)(self, sel_registerName("window"));
    if (!window) return inWindow;
    return ((CGRect (*)(id, SEL, CGRect))objc_msgSend)(window, sel_registerName("convertRectToScreen:"), inWindow);
}

// --- file drag and drop ---
static char *sv_dropped_paths(id sender) {
    id pb = ((id (*)(id, SEL))objc_msgSend)(sender, sel_registerName("draggingPasteboard"));
    id type = ((id (*)(id, SEL, const char *))objc_msgSend)((id)objc_getClass("NSString"), sel_registerName("stringWithUTF8String:"), "NSFilenamesPboardType");
    id list = ((id (*)(id, SEL, id))objc_msgSend)(pb, sel_registerName("propertyListForType:"), type);
    if (!list) return NULL;
    unsigned long n = ((unsigned long (*)(id, SEL))objc_msgSend)(list, sel_registerName("count"));
    size_t cap = 1; char *out = calloc(1, 1);
    for (unsigned long i = 0; i < n; i++) {
        id item = ((id (*)(id, SEL, unsigned long))objc_msgSend)(list, sel_registerName("objectAtIndex:"), i);
        const char *u = sv_utf8(item);
        if (!u) continue;
        size_t add = strlen(u) + 1;
        out = realloc(out, cap + add);
        memcpy(out + cap - 1, u, add - 1); out[cap - 1 + add - 1] = '\n'; out[cap + add - 1] = 0; cap += add;
    }
    return out;
}
static void sv_drag_location(id self, id sender, double *x, double *y) {
    CGPoint inWindow = ((CGPoint (*)(id, SEL))objc_msgSend)(sender, sel_registerName("draggingLocation"));
    CGPoint inView = ((CGPoint (*)(id, SEL, CGPoint, id))objc_msgSend)(self, sel_registerName("convertPoint:fromView:"), inWindow, NULL);
    CGRect b = ((CGRect (*)(id, SEL))objc_msgSend)(self, sel_registerName("bounds"));
    *x = inView.x; *y = b.size.height - inView.y;
}
static unsigned long sv_drag_entered(id self, SEL _cmd, id sender) {
    (void)_cmd; double x, y; sv_drag_location(self, sender, &x, &y);
    char *paths = sv_dropped_paths(sender); sv_push(self, SV_HOVER, x, y, 0, paths ? paths : ""); free(paths);
    return 1; // NSDragOperationCopy
}
static unsigned long sv_drag_updated(id self, SEL _cmd, id sender) {
    (void)_cmd; double x, y; sv_drag_location(self, sender, &x, &y);
    sv_push(self, SV_HOVER, x, y, 1, NULL);
    return 1;
}
static void sv_drag_exited(id self, SEL _cmd, id sender) { (void)_cmd; (void)sender; sv_push(self, SV_HOVER_END, 0, 0, 0, NULL); }
static signed char sv_drag_perform(id self, SEL _cmd, id sender) {
    (void)_cmd; double x, y; sv_drag_location(self, sender, &x, &y);
    char *paths = sv_dropped_paths(sender);
    if (paths && paths[0]) sv_push(self, SV_DROP, x, y, 0, paths);
    free(paths);
    return 1;
}

// --- cursor rects and display scale ---
static void sv_reset_cursor_rects(id self, SEL _cmd) {
    (void)_cmd;
    ScaluiViewState *st = sv_state(self);
    if (!st->hasCursor) return;
    CGRect b = ((CGRect (*)(id, SEL))objc_msgSend)(self, sel_registerName("bounds"));
    ((void (*)(id, SEL, CGRect, id))objc_msgSend)(self, sel_registerName("addCursorRect:cursor:"), b, st->cursor);
}
static void sv_backing_changed(id self, SEL _cmd) {
    (void)_cmd;
    id window = ((id (*)(id, SEL))objc_msgSend)(self, sel_registerName("window"));
    if (window) sv_push(self, SV_SCALE, ((double (*)(id, SEL))objc_msgSend)(window, sel_registerName("backingScaleFactor")), 0, 0, NULL);
}
// called after every size change, including each step of an interactive resize (which blocks the event loop)
static void (*sv_resize_cb)(void) = NULL;
void scalaui_set_resize_callback(void (*cb)(void)) { sv_resize_cb = cb; }
static void sv_set_frame_size(id self, SEL _cmd, CGSize size) {
    struct objc_super sup = { self, objc_getClass("NSView") };
    ((void (*)(struct objc_super *, SEL, CGSize))objc_msgSendSuper)(&sup, _cmd, size);
    if (sv_resize_cb) sv_resize_cb();
}
static void sv_dealloc(id self, SEL _cmd) {
    ScaluiViewState *st = sv_state(self);
    if (st) { sv_clear_marked(self); if (st->access) ((void (*)(id, SEL))objc_msgSend)(st->access, sel_registerName("release")); for (int i = 0; i < st->count; i++) free(st->q[(st->head + i) % SV_QUEUE].text); free(st); *(ScaluiViewState **)((char *)self + sv_state_offset) = NULL; }
    struct objc_super sup = { self, objc_getClass("NSView") };
    ((void (*)(struct objc_super *, SEL))objc_msgSendSuper)(&sup, _cmd);
}


// --- accessibility: a flat list of NSAccessibilityElement subclasses published as the view's children ---
static ptrdiff_t sa_index_offset = 0, sa_view_offset = 0, sa_selloc_offset = 0, sa_sellen_offset = 0;
typedef struct { unsigned long location, length; } SaRange;
static id sv_nsstring(const char *u);
static id sa_value_string(id self) { return ((id (*)(id, SEL))objc_msgSend)(self, sel_registerName("accessibilityValue")); }
static unsigned long sa_length(id s) { return s ? ((unsigned long (*)(id, SEL))objc_msgSend)(s, sel_registerName("length")) : 0; }
static long sa_nchars(id self, SEL _cmd) { (void)_cmd; return (long)sa_length(sa_value_string(self)); }
static SaRange sa_selected_range(id self, SEL _cmd) {
    (void)_cmd;
    SaRange r = { (unsigned long)*(long *)((char *)self + sa_selloc_offset), (unsigned long)*(long *)((char *)self + sa_sellen_offset) };
    unsigned long n = sa_length(sa_value_string(self));
    if (r.location > n) r.location = n;
    if (r.location + r.length > n) r.length = n - r.location;
    return r;
}
static SaRange sa_visible_range(id self, SEL _cmd) { (void)_cmd; SaRange r = { 0, sa_length(sa_value_string(self)) }; return r; }
static id sa_string_for_range(id self, SEL _cmd, SaRange r) {
    (void)_cmd;
    id v = sa_value_string(self); unsigned long n = sa_length(v);
    if (r.location > n) r.location = n;
    if (r.location + r.length > n) r.length = n - r.location;
    return ((id (*)(id, SEL, SaRange))objc_msgSend)(v, sel_registerName("substringWithRange:"), r);
}
static id sa_selected_text(id self, SEL _cmd) { return sa_string_for_range(self, _cmd, sa_selected_range(self, _cmd)); }
static SaRange sa_range_for_index(id self, SEL _cmd, long i) {
    (void)_cmd;
    id v = sa_value_string(self); unsigned long n = sa_length(v);
    if (i < 0 || (unsigned long)i >= n) { SaRange e = { n, 0 }; return e; }
    return ((SaRange (*)(id, SEL, unsigned long))objc_msgSend)(v, sel_registerName("rangeOfComposedCharacterSequenceAtIndex:"), (unsigned long)i);
}
static long sa_line_for_index(id self, SEL _cmd, long i) {
    (void)_cmd;
    id v = sa_value_string(self); unsigned long n = sa_length(v);
    long line = 0;
    for (unsigned long k = 0; k < n && k < (unsigned long)i; k++) {
        unsigned short ch = ((unsigned short (*)(id, SEL, unsigned long))objc_msgSend)(v, sel_registerName("characterAtIndex:"), k);
        if (ch == '\n') line++;
    }
    return line;
}
static SaRange sa_range_for_line(id self, SEL _cmd, long line) {
    (void)_cmd;
    id v = sa_value_string(self); unsigned long n = sa_length(v);
    unsigned long start = 0; long cur = 0; SaRange r = { n, 0 };
    for (unsigned long k = 0; k <= n; k++) {
        int end = k == n;
        unsigned short ch = end ? 0 : ((unsigned short (*)(id, SEL, unsigned long))objc_msgSend)(v, sel_registerName("characterAtIndex:"), k);
        if (end || ch == '\n') {
            if (cur == line) { r.location = start; r.length = (end ? k : k + 1) - start; return r; }
            cur++; start = k + 1;
        }
    }
    return r;
}
static void sa_set_selected_range(id self, SEL _cmd, SaRange r) {
    (void)_cmd;
    id view = *(id *)((char *)self + sa_view_offset);
    long index = *(long *)((char *)self + sa_index_offset);
    if (view) sv_push(view, SV_ACCESS_SELECT, (double)index, (double)r.location, (double)r.length, NULL);
}
static signed char sa_press(id self, SEL _cmd) {
    (void)_cmd;
    id view = *(id *)((char *)self + sa_view_offset);
    long index = *(long *)((char *)self + sa_index_offset);
    if (view) sv_push(view, SV_ACCESS_PRESS, (double)index, 0, 0, NULL);
    return 1;
}
static Class sa_class(void) {
    static Class cls = NULL;
    if (cls) return cls;
    cls = objc_allocateClassPair(objc_getClass("NSAccessibilityElement"), "ScaluiAccessElement", 0);
    class_addIvar(cls, "scaluiIndex", sizeof(long), 3, "q");
    class_addIvar(cls, "scaluiView", sizeof(void *), 3, "^v");
    class_addIvar(cls, "scaluiSelLoc", sizeof(long), 3, "q");
    class_addIvar(cls, "scaluiSelLen", sizeof(long), 3, "q");
    class_addMethod(cls, sel_registerName("accessibilityPerformPress"), (IMP)sa_press, "c@:");
    // text-field protocol (NSAccessibilityStaticText / TextField attributes); harmless on elements that are not text
    class_addMethod(cls, sel_registerName("accessibilityNumberOfCharacters"), (IMP)sa_nchars, "q@:");
    class_addMethod(cls, sel_registerName("accessibilitySelectedTextRange"), (IMP)sa_selected_range, "{_NSRange=QQ}@:");
    class_addMethod(cls, sel_registerName("accessibilityVisibleCharacterRange"), (IMP)sa_visible_range, "{_NSRange=QQ}@:");
    class_addMethod(cls, sel_registerName("accessibilitySelectedText"), (IMP)sa_selected_text, "@@:");
    class_addMethod(cls, sel_registerName("accessibilityStringForRange:"), (IMP)sa_string_for_range, "@@:{_NSRange=QQ}");
    class_addMethod(cls, sel_registerName("accessibilityRangeForIndex:"), (IMP)sa_range_for_index, "{_NSRange=QQ}@:q");
    class_addMethod(cls, sel_registerName("accessibilityLineForIndex:"), (IMP)sa_line_for_index, "q@:q");
    class_addMethod(cls, sel_registerName("accessibilityRangeForLine:"), (IMP)sa_range_for_line, "{_NSRange=QQ}@:q");
    class_addMethod(cls, sel_registerName("setAccessibilitySelectedTextRange:"), (IMP)sa_set_selected_range, "v@:{_NSRange=QQ}");
    objc_registerClassPair(cls);
    sa_index_offset = ivar_getOffset(class_getInstanceVariable(cls, "scaluiIndex"));
    sa_view_offset = ivar_getOffset(class_getInstanceVariable(cls, "scaluiView"));
    sa_selloc_offset = ivar_getOffset(class_getInstanceVariable(cls, "scaluiSelLoc"));
    sa_sellen_offset = ivar_getOffset(class_getInstanceVariable(cls, "scaluiSelLen"));
    return cls;
}
static id sv_nsstring(const char *u) {
    return ((id (*)(id, SEL, const char *))objc_msgSend)((id)objc_getClass("NSString"), sel_registerName("stringWithUTF8String:"), u ? u : "");
}
static id sv_access_children(id self, SEL _cmd) {
    (void)_cmd;
    ScaluiViewState *st = sv_state(self);
    if (st && st->access) return st->access;
    return ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSArray"), sel_registerName("array"));
}
static id sv_access_role(id self, SEL _cmd) { (void)self; (void)_cmd; return sv_nsstring("AXGroup"); }

// flags: bit0 focused, bit1 disabled, bit2 has checked value, bit3 checked. `bounds` holds x, y, w, h per node in view
// points with a top-left origin. Roles are AX role strings; an AXSwitch role is mapped to a checkbox with the switch subrole.
void scalaui_view_set_access(id self, int n, const char **roles, const char **labels, const char **values, const double *bounds, const int *flags) {
    ScaluiViewState *st = sv_state(self);
    CGRect b = ((CGRect (*)(id, SEL))objc_msgSend)(self, sel_registerName("bounds"));
    id arr = ((id (*)(id, SEL, unsigned long))objc_msgSend)(((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSMutableArray"), sel_registerName("alloc")), sel_registerName("initWithCapacity:"), (unsigned long)n);
    Class ec = sa_class();
    for (int i = 0; i < n; i++) {
        id e = ((id (*)(id, SEL))objc_msgSend)(((id (*)(id, SEL))objc_msgSend)((id)ec, sel_registerName("alloc")), sel_registerName("init"));
        *(long *)((char *)e + sa_index_offset) = i;
        *(id *)((char *)e + sa_view_offset) = self;
        int isSwitch = strcmp(roles[i], "AXSwitch") == 0;
        ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityRole:"), sv_nsstring(isSwitch ? "AXCheckBox" : roles[i]));
        if (isSwitch) ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilitySubrole:"), sv_nsstring("AXSwitch"));
        if (labels[i] && labels[i][0]) ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityLabel:"), sv_nsstring(labels[i]));
        int f = flags[i];
        if (f & 4) {
            id num = ((id (*)(id, SEL, int))objc_msgSend)((id)objc_getClass("NSNumber"), sel_registerName("numberWithInt:"), (f & 8) ? 1 : 0);
            ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityValue:"), num);
        } else if (values[i] && values[i][0]) {
            ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityValue:"), sv_nsstring(values[i]));
        }
        ((void (*)(id, SEL, signed char))objc_msgSend)(e, sel_registerName("setAccessibilityFocused:"), (f & 1) ? 1 : 0);
        ((void (*)(id, SEL, signed char))objc_msgSend)(e, sel_registerName("setAccessibilityEnabled:"), (f & 2) ? 0 : 1);
        ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityParent:"), self);
        CGRect fr = CGRectMake(bounds[4 * i], b.size.height - bounds[4 * i + 1] - bounds[4 * i + 3], bounds[4 * i + 2], bounds[4 * i + 3]);
        ((void (*)(id, SEL, CGRect))objc_msgSend)(e, sel_registerName("setAccessibilityFrameInParentSpace:"), fr);
        ((void (*)(id, SEL, id))objc_msgSend)(arr, sel_registerName("addObject:"), e);
        ((void (*)(id, SEL))objc_msgSend)(e, sel_registerName("release"));
    }
    id old = st->access;
    st->access = arr;
    if (old) {
        // elements must not call back into a view that outlives them; they only hold an unretained pointer, and we drop them here
        ((void (*)(id, SEL))objc_msgSend)(old, sel_registerName("release"));
    }
    void (*post)(id, id) = (void (*)(id, id))dlsym(RTLD_DEFAULT, "NSAccessibilityPostNotification");
    if (post) post(self, sv_nsstring("AXLayoutChanged"));
}

// Text-field state for the elements published by scalaui_view_set_access: per node 4 ints {kind, caret, selStart, selEnd}
// (kind 0 not text, 1 single line, 2 multi line; offsets are UTF-16 units of the node's value). Multi-line fields get AXTextArea.
void scalaui_view_set_access_text(id self, int n, const int *info) {
    ScaluiViewState *st = sv_state(self);
    if (!st || !st->access) return;
    for (int i = 0; i < n; i++) {
        if (info[4 * i] == 0) continue;
        id e = ((id (*)(id, SEL, unsigned long))objc_msgSend)(st->access, sel_registerName("objectAtIndex:"), (unsigned long)i);
        int a = info[4 * i + 2], b = info[4 * i + 3];
        *(long *)((char *)e + sa_selloc_offset) = a < b ? a : b;
        *(long *)((char *)e + sa_sellen_offset) = a < b ? b - a : a - b;
        if (info[4 * i] == 2) ((void (*)(id, SEL, id))objc_msgSend)(e, sel_registerName("setAccessibilityRole:"), sv_nsstring("AXTextArea"));
    }
}

// Struct-by-value calls on a published element, for callers (tests) that cannot pass NSRange themselves.
void scalaui_access_selection(id e, unsigned long *out) { SaRange r = ((SaRange (*)(id, SEL))objc_msgSend)(e, sel_registerName("accessibilitySelectedTextRange")); out[0] = r.location; out[1] = r.length; }
void scalaui_access_set_selection(id e, unsigned long loc, unsigned long len) { SaRange r = { loc, len }; ((void (*)(id, SEL, SaRange))objc_msgSend)(e, sel_registerName("setAccessibilitySelectedTextRange:"), r); }
id scalaui_access_string_for_range(id e, unsigned long loc, unsigned long len) { SaRange r = { loc, len }; return ((id (*)(id, SEL, SaRange))objc_msgSend)(e, sel_registerName("accessibilityStringForRange:"), r); }

// Tells assistive technologies that the text (kind 0) or the selection (kind 1) of published element `index` changed.
void scalaui_view_post_text(id self, int index, int kind) {
    ScaluiViewState *st = sv_state(self);
    if (!st || !st->access) return;
    unsigned long count = ((unsigned long (*)(id, SEL))objc_msgSend)(st->access, sel_registerName("count"));
    if (index < 0 || (unsigned long)index >= count) return;
    id e = ((id (*)(id, SEL, unsigned long))objc_msgSend)(st->access, sel_registerName("objectAtIndex:"), (unsigned long)index);
    void (*post)(id, id) = (void (*)(id, id))dlsym(RTLD_DEFAULT, "NSAccessibilityPostNotification");
    if (post) post(e, sv_nsstring(kind == 0 ? "AXValueChanged" : "AXSelectedTextChanged"));
}

static Class sv_class(void) {
    static Class cls = NULL;
    if (cls) return cls;
    cls = objc_allocateClassPair(objc_getClass("NSView"), "ScaluiView", 0);
    class_addIvar(cls, "scaluiState", sizeof(void *), 3, "^v");
    #define M(name, imp, types) class_addMethod(cls, sel_registerName(name), (IMP)(imp), types)
    M("acceptsFirstResponder", sv_yes, "c@:");
    M("performKeyEquivalent:", sv_no, "c@:@");
    M("keyDown:", sv_noop1, "v@:@"); M("keyUp:", sv_noop1, "v@:@"); M("flagsChanged:", sv_noop1, "v@:@");
    M("insertText:replacementRange:", sv_insert_text, "v@:@{_NSRange=QQ}");
    M("setMarkedText:selectedRange:replacementRange:", sv_set_marked, "v@:@{_NSRange=QQ}{_NSRange=QQ}");
    M("unmarkText", sv_unmark, "v@:");
    M("hasMarkedText", sv_has_marked, "c@:");
    M("markedRange", sv_marked_range, "{_NSRange=QQ}@:");
    M("selectedRange", sv_selected_range, "{_NSRange=QQ}@:");
    M("attributedSubstringForProposedRange:actualRange:", sv_substring, "@@:{_NSRange=QQ}^{_NSRange=QQ}");
    M("validAttributesForMarkedText", sv_valid_attrs, "@@:");
    M("firstRectForCharacterRange:actualRange:", sv_first_rect, "{CGRect={CGPoint=dd}{CGSize=dd}}@:{_NSRange=QQ}^{_NSRange=QQ}");
    M("characterIndexForPoint:", sv_char_index, "Q@:{CGPoint=dd}");
    M("doCommandBySelector:", sv_do_command, ":@::");
    M("draggingEntered:", sv_drag_entered, "Q@:@"); M("draggingUpdated:", sv_drag_updated, "Q@:@");
    M("draggingExited:", sv_drag_exited, "v@:@"); M("performDragOperation:", sv_drag_perform, "c@:@");
    M("prepareForDragOperation:", sv_yes, "c@:@"); M("concludeDragOperation:", sv_noop1, "v@:@");
    M("resetCursorRects", sv_reset_cursor_rects, "v@:");
    M("viewDidChangeBackingProperties", sv_backing_changed, "v@:");
    M("accessibilityChildren", sv_access_children, "@@:");
    M("isAccessibilityElement", sv_no, "c@:");
    M("accessibilityRole", sv_access_role, "@@:");
    M("scaluiMenuAction:", sv_menu_action, "v@:@");
    M("scaluiQuit:", sv_quit_action, "v@:@");
    M("setFrameSize:", sv_set_frame_size, "v@:{CGSize=dd}");
    M("dealloc", sv_dealloc, "v@:");
    #undef M
    Protocol *tic = objc_getProtocol("NSTextInputClient"); if (tic) class_addProtocol(cls, tic);
    objc_registerClassPair(cls);
    sv_state_offset = ivar_getOffset(class_getInstanceVariable(cls, "scaluiState"));
    return cls;
}

// creates a ScaluiView of the given size (view coordinates, origin bottom-left)
id scalaui_view_create(double w, double h) {
    Class c = sv_class();
    id v = ((id (*)(id, SEL))objc_msgSend)((id)c, sel_registerName("alloc"));
    v = ((id (*)(id, SEL, CGRect))objc_msgSend)(v, sel_registerName("initWithFrame:"), CGRectMake(0, 0, w, h));
    *(ScaluiViewState **)((char *)v + sv_state_offset) = calloc(1, sizeof(ScaluiViewState));
    return v;
}

void scalaui_view_set_ime_rect(id self, double x, double y, double w, double h) {
    ScaluiViewState *st = sv_state(self);
    st->imeRect[0] = x; st->imeRect[1] = y; st->imeRect[2] = w; st->imeRect[3] = h;
}

// `cursor` may be NULL for "no custom cursor"
void scalaui_view_set_cursor(id self, id cursor) {
    ScaluiViewState *st = sv_state(self);
    st->cursor = cursor; st->hasCursor = cursor != NULL;
    id window = ((id (*)(id, SEL))objc_msgSend)(self, sel_registerName("window"));
    if (window) ((void (*)(id, SEL, id))objc_msgSend)(window, sel_registerName("invalidateCursorRectsForView:"), self);
}

// ---- window helpers that take struct arguments ----
void scalaui_window_set_min_size(id win, double w, double h) { ((void (*)(id, SEL, CGSize))objc_msgSend)(win, sel_registerName("setContentMinSize:"), CGSizeMake(w, h)); }
void scalaui_window_set_origin(id win, double x, double y) { ((void (*)(id, SEL, CGPoint))objc_msgSend)(win, sel_registerName("setFrameOrigin:"), CGPointMake(x, y)); }
void scalaui_window_origin(id win, double *out) { CGRect f = ((CGRect (*)(id, SEL))objc_msgSend)(win, sel_registerName("frame")); out[0] = f.origin.x; out[1] = f.origin.y; out[2] = f.size.width; out[3] = f.size.height; }
// wraps a CGImage as an NSImage of `w x h` points (retained)
id scalaui_nsimage(void *cgimage, double w, double h) {
    id ns = ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSImage"), sel_registerName("alloc"));
    return ((id (*)(id, SEL, void *, CGSize))objc_msgSend)(ns, sel_registerName("initWithCGImage:size:"), cgimage, CGSizeMake(w, h));
}
// installs a CGImage (`w x h` points) as the application icon; CoreGraphics itself is dlopen'ed from Scala
void scalaui_set_app_icon(void *cgimage, double w, double h) {
    id ns = ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSImage"), sel_registerName("alloc"));
    ns = ((id (*)(id, SEL, void *, CGSize))objc_msgSend)(ns, sel_registerName("initWithCGImage:size:"), cgimage, CGSizeMake(w, h));
    id app = ((id (*)(id, SEL))objc_msgSend)((id)objc_getClass("NSApplication"), sel_registerName("sharedApplication"));
    ((void (*)(id, SEL, id))objc_msgSend)(app, sel_registerName("setApplicationIconImage:"), ns);
}
#endif // __APPLE__
