# WebView 的 JS 桥接方法不能被混淆或移除
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
