package com.manus.kavitaink.core

import org.json.JSONObject

/**
 * EPUB 分页：用 CSS 多栏布局把章节排成“每屏一栏”，翻页时只是把视口水平移动一屏宽，
 * 没有滚动动画，墨水屏每次翻页只刷新一次。
 *
 *  - 样式在 WebView 请求章节 HTML 时直接插入 </head> 前，首帧即为分页效果（避免先显示长页面再跳变）
 *  - 加载完成后注入脚本，按真实视口尺寸校正样式、计算页数，并通过 EinkBridge.onLayout 回传
 */
object Paginator {

    data class Style(
        val marginX: Int = 16,
        val marginTop: Int = 18,
        val marginBottom: Int = 18,
        val lineHeight: Int = 160, // 百分比；0 = 使用书籍自带行距
        /** null = 书籍默认；"serif" / "sans-serif" = 系统字体；其它 = 已安装字体（需配合 fontUrl） */
        val fontFamily: String? = null,
        /** 已安装字体的各个变体（地址由阅读器拦截请求提供文件） */
        val fontFaces: List<FontFace> = emptyList(),
        val forceBlack: Boolean = true,
        /** true = 首行缩进 / 段间距 / 对齐 / 字间距使用书里自带的样式 */
        val bookStyle: Boolean = true,
        val textIndent: Int = 2,    // 字（em）
        val paraSpacing: Int = 5,   // 0.1 行
        val justify: Boolean = true,
        val letterSpacing: Int = 0, // 0.01 em
    )

    /** 一个字体变体：url 只能含 ASCII 且不含 < & " ' ( ) \\ */
    data class FontFace(val url: String, val style: String = "normal", val weight: String = "400")

    private const val TEXT_SEL = "body,p,div,span,li,dd,dt,blockquote,h1,h2,h3,h4,h5,h6,a"

    /** 模板只含 ASCII 且不含 < 和 &，可以安全插入 XHTML */
    private fun template(s: Style): String {
        val sb = StringBuilder()
        // html 宽度固定为整数像素：栏距 = 宽度，翻页平移距离与之完全一致（高密度屏视口宽度常为小数，否则每页会累积误差）
        sb.append("html{margin:0 !important;padding:0 !important;width:__W__px !important;max-width:__W__px !important;min-width:0 !important;")
        sb.append("height:__H__px !important;overflow:hidden !important;")
        sb.append("background:#fff !important;writing-mode:horizontal-tb !important;-webkit-writing-mode:horizontal-tb !important;")
        sb.append("scroll-behavior:auto !important;-webkit-text-size-adjust:none;}")
        sb.append("body{margin:0 !important;box-sizing:border-box !important;height:__H__px !important;min-height:0 !important;")
        sb.append("width:auto !important;max-width:none !important;")
        sb.append("padding:__MT__px __MX__px __MB__px __MX__px !important;")
        sb.append("-webkit-column-width:__CW__px !important;column-width:__CW__px !important;")
        sb.append("-webkit-column-gap:__GAP__px !important;column-gap:__GAP__px !important;")
        sb.append("-webkit-column-fill:auto !important;column-fill:auto !important;")
        sb.append("overflow:visible !important;background:#fff !important;")
        sb.append("writing-mode:horizontal-tb !important;-webkit-writing-mode:horizontal-tb !important;}")
        sb.append("img,svg,video,object{max-width:100% !important;max-height:__IH__px !important;")
        sb.append("-webkit-column-break-inside:avoid;break-inside:avoid;object-fit:contain;}")
        sb.append("img{height:auto;}")
        sb.append("pre{white-space:pre-wrap !important;}table{max-width:100% !important;}")
        // 比一行还宽的词（长网址、连续的不换行空格）允许断开，否则会伸进下一栏被截断；普通文字不受影响
        sb.append("body{word-wrap:break-word !important;overflow-wrap:break-word !important;}")
        if (s.lineHeight > 0) {
            val lh = String.format(java.util.Locale.US, "%.2f", s.lineHeight / 100.0)
            sb.append("p,div,li,dd,dt,blockquote,span{line-height:$lh !important;}")
        }
        val faces = safeFaces(s)
        when {
            faces.isNotEmpty() -> {
                // 每个变体一条 @font-face；字体里缺的字（例如西文字体遇到中文）自动回退到系统字体
                for (f in faces) {
                    sb.append("@font-face{font-family:\"__einkfont\";src:url(\"").append(f.url).append("\");")
                    sb.append("font-style:").append(FontStore.cssStyle(f.style)).append(";")
                    sb.append("font-weight:").append(FontStore.cssWeight(f.weight)).append(";}")
                }
                sb.append(TEXT_SEL).append("{font-family:\"__einkfont\",serif !important;}")
            }
            s.fontFamily == "serif" -> sb.append(TEXT_SEL).append("{font-family:serif !important;}")
            s.fontFamily == "sans-serif" -> sb.append(TEXT_SEL).append("{font-family:sans-serif !important;}")
        }
        if (!s.bookStyle) {
            val lhEm = if (s.lineHeight > 0) s.lineHeight / 100.0 else 1.5
            val gap = s.paraSpacing / 10.0 * lhEm
            // 规则挂在 html.__einkx 下：分页脚本先按书籍原样式标出居中 / 右对齐 / 只含图片的段落（__einkn），
            // 再加上 __einkx 让规则生效。这些段落保持原来的对齐方式，也不缩进（WebView 66 不支持 :has()）
            sb.append("html.__einkx p:not(.__einkn){text-indent:").append(s.textIndent.coerceIn(0, 4)).append("em !important;")
            sb.append("text-align:").append(if (s.justify) "justify" else "left").append(" !important;}")
            sb.append("html.__einkx p.__einkn{text-indent:0 !important;}")
            sb.append("html.__einkx p{margin-top:0 !important;margin-bottom:")
                .append(String.format(java.util.Locale.US, "%.2f", gap)).append("em !important;}")
            sb.append("p,div,span,li,dd,dt,blockquote{letter-spacing:")
                .append(String.format(java.util.Locale.US, "%.2f", s.letterSpacing / 100.0)).append("em !important;}")
        }
        if (s.forceBlack) {
            sb.append("*{color:#000 !important;background-color:transparent !important;text-shadow:none !important;}")
            sb.append("html,body{background-color:#fff !important;}")
        }
        return sb.toString()
    }

    fun css(s: Style, w: Int, h: Int): String = fill(template(s), s, w, h)

    private fun fill(t: String, s: Style, w: Int, h: Int): String = t
        .replace("__W__", w.toString())
        .replace("__H__", h.toString())
        .replace("__CW__", (w - 2 * s.marginX).coerceAtLeast(50).toString())
        .replace("__GAP__", (2 * s.marginX).toString())
        .replace("__IH__", (h - s.marginTop - s.marginBottom).coerceAtLeast(50).toString())
        .replace("__MT__", s.marginTop.toString())
        .replace("__MB__", s.marginBottom.toString())
        .replace("__MX__", s.marginX.toString())

    /**
     * 锁定缩放为 1。老版 Android WebView（如 Chrome 66）在内容横向溢出时会自动缩小页面，
     * 导致 innerWidth 变大、分栏失效（整章排成一栏）——这一行是关键。
     */
    const val VIEWPORT_META =
        "<meta id=\"__einkvp\" name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no\"/>"

    /**
     * 自定义排版用：按书籍原样式标出居中 / 右对齐 / 只含图片的段落（__einkn），再给 html 加 __einkx 启用自定义规则。
     * 跟样式一起注入到 <head>，在 DOMContentLoaded 时执行（此时还没排版，只多一次样式计算，不会多排一次版）；
     * 分页脚本里再调用一次兜底（已标记过则直接跳过）。不含 < 和 &，可以安全放进 XHTML。
     */
    private val MARK_JS = """(function(){function m(){var de=document.documentElement;""" +
        """if(/(^|\s)__einkx(\s|${'$'})/.test(de.className))return;""" +
        """var ps=document.getElementsByTagName('p'),i,pe,ta,only;""" +
        """for(i=0;ps.length>i;i++){pe=ps[i];ta=getComputedStyle(pe).textAlign;only=false;""" +
        """if(!/\S/.test((pe.textContent||'').replace(/\u00a0/g,' ')))only=!!pe.querySelector('img,svg,image,video,object');""" +
        """if(ta=='center'||ta=='right'||ta=='end'||ta=='-webkit-center'||ta=='-webkit-right'||only)""" +
        """pe.className=(pe.className?pe.className+' ':'')+'__einkn';}""" +
        """de.className=(de.className?de.className+' ':'')+'__einkx';}""" +
        """if(document.readyState=='loading')document.addEventListener('DOMContentLoaded',m,false);else m();})();"""

    /** 含占位符的注入片段（测试工具用同一份模板） */
    fun headTemplate(s: Style): String =
        VIEWPORT_META + "<style id=\"__einkcss\" type=\"text/css\" data-w=\"__W__\" data-h=\"__H__\">" + template(s) + "</style>" +
            (if (s.bookStyle) "" else "<script id=\"__einkmark\" type=\"text/javascript\">$MARK_JS</script>")

    fun styleTag(s: Style, w: Int, h: Int): String = fill(headTemplate(s), s, w, h)

    /** 把样式插入到 </head> 前（没有 head 时补一个放在 <body 前）；字节级处理（ISO-8859-1 往返不改变原有编码） */
    fun injectStyle(bytes: ByteArray, tag: String): ByteArray {
        if (bytes.size >= 2 && ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))) {
            return bytes
        }
        val s = String(bytes, Charsets.ISO_8859_1)
        val m = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            val idx = m.range.first
            return (s.substring(0, idx) + tag + s.substring(idx)).toByteArray(Charsets.ISO_8859_1)
        }
        val b = Regex("<body[\\s>]", RegexOption.IGNORE_CASE).find(s) ?: return bytes
        val idx = b.range.first
        return (s.substring(0, idx) + "<head>" + tag + "</head>" + s.substring(idx)).toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * 页面加载完成后执行。定义：
     *   __einkCount() 页数；__einkGo(p) 跳到第 p 页；__einkFind(id) 锚点所在页
     * hintW/hintH 是原生端按 WebView 实际像素 / 屏幕密度算出的 CSS 尺寸，优先使用（不受页面缩放影响）。
     */
    fun script(gen: Int, s: Style, hintW: Int = 0, hintH: Int = 0): String {
        val tpl = JSONObject.quote(template(s))
        val meta = JSONObject.quote("width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no")
        return """
(function(){
  var G=$gen, MX=${s.marginX}, MT=${s.marginTop}, MB=${s.marginBottom}, HW=$hintW, HH=$hintH;
  var tpl=$tpl;
  function css(w,h){
    return tpl.split('__W__').join(w).split('__H__').join(h)
      .split('__CW__').join(Math.max(50,w-2*MX)).split('__GAP__').join(2*MX)
      .split('__IH__').join(Math.max(50,h-MT-MB)).split('__MT__').join(MT)
      .split('__MB__').join(MB).split('__MX__').join(MX);
  }
  var head=document.head||document.getElementsByTagName('head')[0]||document.documentElement;
  if(!document.getElementById('__einkvp')){
    var vm=document.createElement('meta');
    vm.setAttribute('id','__einkvp'); vm.setAttribute('name','viewport'); vm.setAttribute('content',$meta);
    head.appendChild(vm);
  }
  var w=HW>0?HW:Math.floor(window.innerWidth||document.documentElement.clientWidth);
  var h=HH>0?HH:Math.floor(window.innerHeight||document.documentElement.clientHeight);
  var st=document.getElementById('__einkcss');
  if(!st){
    st=document.createElement('style');
    st.setAttribute('id','__einkcss');
    head.appendChild(st);
    st.textContent=css(w,h);
  } else if(st.getAttribute('data-w')!=String(w)||st.getAttribute('data-h')!=String(h)){
    st.textContent=css(w,h);
  }
  st.setAttribute('data-w',w); st.setAttribute('data-h',h);
  window.__einkW=w;
  var cur=0;
  // 书籍样式里常见 page-break-after:always（如封面），在最末尾元素上会多分出一个空白页，去掉它
  try{
    var e=document.body;
    while(e && e.lastElementChild){
      e=e.lastElementChild;
      if(e.style){
        e.style.setProperty('break-after','auto','important');
        e.style.setProperty('page-break-after','auto','important');
        e.style.setProperty('-webkit-column-break-after','auto','important');
      }
    }
  }catch(err){}
  // 自定义排版：兜底标记（正常情况下 <head> 里注入的脚本已在 DOMContentLoaded 时标记完，这里直接跳过）
  if(${if (s.bookStyle) 0 else 1}){ try{ $MARK_JS }catch(err){} }
  var MEDIA={IMG:1,SVG:1,IMAGE:1,VIDEO:1,HR:1,TABLE:1,CANVAS:1,OBJECT:1,EMBED:1,IFRAME:1,MATH:1};
  // 最后一段实际内容（文字或图片）所在的页；分栏按文档顺序填充，所以它就是最后一页
  function lastContentPage(){
    try{
      var b=document.body; if(!b) return -1;
      var tw=document.createTreeWalker(b, 5, null, false), n, last=null; // 5 = SHOW_ELEMENT|SHOW_TEXT
      while((n=tw.nextNode())){
        if(n.nodeType===3){ if(/\S/.test(n.nodeValue)) last=n; }
        else if(MEDIA[String(n.tagName).toUpperCase()]) last=n;
      }
      if(!last) return -1;
      var x;
      if(last.nodeType===3){
        var r=document.createRange(); r.selectNodeContents(last);
        var rs=r.getClientRects(), lr=null;
        for(var i=rs.length-1;i>=0;i--){ if(rs[i].width>0||rs[i].height>0){ lr=rs[i]; break; } }
        if(!lr) return -1;
        x=lr.left+1;
      } else {
        var br=last.getBoundingClientRect();
        if(br.width<=0&&br.height<=0) return -1;
        x=br.right-1;
      }
      return Math.max(0, Math.floor(x/w));
    }catch(err){ return -1; }
  }
  window.__einkCount=function(){
    var de=document.documentElement, b=document.body;
    if(b){ b.style.webkitTransform=''; b.style.transform=''; }
    var sw=Math.max(de.scrollWidth, b?b.scrollWidth:0);
    var n=Math.max(1, Math.ceil((sw-2)/w));
    var lp=lastContentPage();
    if(lp>=0 && lp+1<n) n=lp+1;
    if(b && cur){ var s='translate('+(-cur)+'px,0px)'; b.style.webkitTransform=s; b.style.transform=s; }
    return n;
  };
  // 用 transform 平移而不是滚动：滚动会被最后一栏的右边界截住，导致末页错位
  window.__einkGo=function(p){
    var b=document.body; if(!b) return 0;
    cur=p*w;
    var t='translate('+(-cur)+'px,0px)';
    b.style.webkitTransform=t; b.style.transform=t;
    window.scrollTo(0,0);
    return p;
  };
  window.__einkFind=function(id){
    var el=document.getElementById(id);
    if(!el){var a=document.getElementsByName(id); el=a&&a[0];}
    if(!el) return -1;
    var r=el.getBoundingClientRect();
    var x=r.left+cur;
    return Math.max(0, Math.floor((x+1)/w));
  };
  function report(){
    try{ EinkBridge.onLayout(G, window.__einkCount()); }catch(e){}
  }
  function whenReady(){
    if(document.fonts&&document.fonts.ready){
      document.fonts.ready.then(function(){ setTimeout(report,0); }, function(){ setTimeout(report,0); });
    } else { setTimeout(report,30); }
  }
  // 自定义字体按需加载：先显式触发下载，等它就绪后再计算页数（否则页数按回退字体算，会错）
  if(${if (customFont(s)) 1 else 0} && document.fonts && document.fonts.load){
    document.fonts.load('1em "__einkfont"').then(whenReady, whenReady);
  } else { whenReady(); }
})();
""".trimIndent()
    }

    private fun customFont(s: Style) = safeFaces(s).isNotEmpty()

    private fun safeFaces(s: Style): List<FontFace> = s.fontFaces.filter { safeUrl(it.url) != null }

    private fun safeUrl(u: String?): String? =
        u?.takeIf { v -> v.isNotEmpty() && v.all { it.code in 0x21..0x7E && it !in "<&\"'()\\" } }
}
