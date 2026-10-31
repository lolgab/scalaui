#include <windows.h>
#include <uiautomation.h>
#include <stdio.h>
#include <string>
static std::string narrow(BSTR b) { if (!b) return ""; int n = WideCharToMultiByte(CP_UTF8, 0, b, -1, 0, 0, 0, 0); std::string s(n, 0); WideCharToMultiByte(CP_UTF8, 0, b, -1, &s[0], n, 0, 0); s.resize(n ? n - 1 : 0); return s; }
int main() {
  CoInitializeEx(NULL, COINIT_MULTITHREADED);
  IUIAutomation* a = NULL;
  HRESULT hr = CoCreateInstance(CLSID_CUIAutomation, NULL, CLSCTX_INPROC_SERVER, IID_IUIAutomation, (void**)&a);
  printf("CoCreateInstance hr=%08lx\n", hr); fflush(stdout);
  if (FAILED(hr)) return 2;
  HWND hwnd = NULL;
  for (int i = 0; i < 100 && !hwnd; i++) { hwnd = FindWindowW(L"ScalauiWindow", NULL); if (!hwnd) Sleep(100); }
  printf("hwnd=%p\n", hwnd); fflush(stdout);
  if (!hwnd) return 3;
  IUIAutomationElement* root = NULL;
  hr = a->ElementFromHandle(hwnd, &root);
  printf("ElementFromHandle hr=%08lx\n", hr); fflush(stdout);
  if (FAILED(hr) || !root) return 4;
  BSTR name = NULL; root->get_CurrentName(&name); CONTROLTYPEID ct = 0; root->get_CurrentControlType(&ct);
  printf("root name='%s' type=%d\n", narrow(name).c_str(), (int)ct);
  IUIAutomationCondition* cond = NULL; a->CreateTrueCondition(&cond);
  IUIAutomationElementArray* arr = NULL; hr = root->FindAll(TreeScope_Children, cond, &arr);
  printf("FindAll hr=%08lx arr=%p\n", hr, arr); fflush(stdout);
  int n = 0; IUIAutomationElement* button = NULL;
  if (SUCCEEDED(hr) && arr) {
    int len = 0; arr->get_Length(&len);
    for (int i = 0; i < len; i++) {
      IUIAutomationElement* c = NULL; arr->GetElement(i, &c);
      BSTR nm = NULL; c->get_CurrentName(&nm); CONTROLTYPEID t = 0; c->get_CurrentControlType(&t);
      BOOL en = TRUE;
      std::string s2 = narrow(nm);
      if (i < 14) printf("  [%d] type=%d enabled=%d name='%s'\n", i, (int)t, (int)en, s2.c_str());
      if (t == UIA_ButtonControlTypeId && s2 == "Click me") button = c;
      n++;
    }
  }
  printf("children=%d\n", n);
  if (button) {
    RECT r; button->get_CurrentBoundingRectangle(&r);
    printf("button rect %ld,%ld %ldx%ld\n", r.left, r.top, r.right - r.left, r.bottom - r.top);
    IUnknown* p = NULL; hr = button->GetCurrentPattern(UIA_InvokePatternId, &p);
    printf("invoke pattern hr=%08lx\n", hr); fflush(stdout);
    if (SUCCEEDED(hr) && p) { IUIAutomationInvokePattern* ip = NULL; p->QueryInterface(IID_IUIAutomationInvokePattern, (void**)&ip); if (ip) { hr = ip->Invoke(); printf("Invoke hr=%08lx\n", hr); } }
  }
  printf("UIA CLIENT DONE\n"); fflush(stdout);
  return 0;
}
