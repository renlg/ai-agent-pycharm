package com.taiwei.aiagent.browser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class BrowserToolService implements Disposable {

    private static final Logger LOG = Logger.getInstance(BrowserToolService.class);
    private static final long JS_EVAL_TIMEOUT_SECONDS = 10;
    private static final int MAX_CONTENT_LENGTH = 50000;
    private static final int MAX_CAPTURED_REQUESTS = 500;

    private final Project project;
    private volatile JBCefBrowser browser;
    private volatile JBCefJSQuery jsQuery;
    private volatile String bridgeInjectCode;
    private volatile boolean networkCaptureEnabled = false;
    private volatile Consumer<String> urlChangeListener;
    private final CopyOnWriteArrayList<String> capturedRequests = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingEvaluations = new ConcurrentHashMap<>();

    public BrowserToolService(Project project) {
        this.project = project;
    }

    public static BrowserToolService getInstance(Project project) {
        return project.getService(BrowserToolService.class);
    }

    public void setUrlChangeListener(Consumer<String> listener) {
        this.urlChangeListener = listener;
    }

    private void notifyUrlChanged(String url) {
        Consumer<String> listener = urlChangeListener;
        if (listener != null) {
            listener.accept(url);
        }
    }

    public synchronized JBCefBrowser getOrCreateBrowser() {
        if (browser == null) {
            browser = new JBCefBrowser("about:blank");
            setupJsBridge();
            setupLoadHandler();
        }
        return browser;
    }

    public JComponent getBrowserComponent() {
        return getOrCreateBrowser().getComponent();
    }

    private void setupJsBridge() {
        jsQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        jsQuery.addHandler(request -> {
            handleJsMessage(request);
            return new JBCefJSQuery.Response("ok");
        });
        bridgeInjectCode = jsQuery.inject("msg");
    }

    private void setupLoadHandler() {
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.isMain()) {
                    injectBridgeFunction();
                    if (networkCaptureEnabled) {
                        cefBrowser.executeJavaScript(getNetworkCaptureScript(), "taiwei-netcap", 0);
                    }
                    String url = cefBrowser.getURL();
                    if (url != null && !url.isEmpty()) {
                        notifyUrlChanged(url);
                    }
                }
            }
        }, browser.getCefBrowser());
    }

    private void injectBridgeFunction() {
        String js = "window.taiweiBrowserQuery = function(msg) { " + bridgeInjectCode + " };";
        browser.getCefBrowser().executeJavaScript(js, "taiwei-bridge", 0);
    }

    private void handleJsMessage(String msg) {
        if (msg.startsWith("jsresult:")) {
            String rest = msg.substring(9);
            int colonIdx = rest.indexOf(':');
            if (colonIdx > 0) {
                String id = rest.substring(0, colonIdx);
                String result = rest.substring(colonIdx + 1);
                CompletableFuture<String> future = pendingEvaluations.remove(id);
                if (future != null) {
                    future.complete(result);
                }
            }
        } else if (msg.startsWith("jserror:")) {
            String rest = msg.substring(8);
            int colonIdx = rest.indexOf(':');
            if (colonIdx > 0) {
                String id = rest.substring(0, colonIdx);
                String error = rest.substring(colonIdx + 1);
                CompletableFuture<String> future = pendingEvaluations.remove(id);
                if (future != null) {
                    future.completeExceptionally(new RuntimeException(error));
                }
            }
        } else if (msg.startsWith("netcap:")) {
            String json = msg.substring(7);
            capturedRequests.add(json);
            // Keep the capture buffer bounded: a long-lived page can otherwise grow it without limit.
            while (capturedRequests.size() > MAX_CAPTURED_REQUESTS) {
                capturedRequests.remove(0);
            }
        }
    }

    public void openUrl(String url) {
        JBCefBrowser b = getOrCreateBrowser();
        notifyUrlChanged(url);
        SwingUtilities.invokeLater(() -> b.loadURL(url));
    }

    public String getContent(String contentType) {
        String expression;
        if ("text".equals(contentType)) {
            expression = "document.body ? document.body.innerText : ''";
        } else {
            expression = "document.documentElement.outerHTML";
        }
        String result = evaluateExpression(expression);
        if (result != null && result.length() > MAX_CONTENT_LENGTH) {
            return result.substring(0, MAX_CONTENT_LENGTH) + I18nUtil.getMessage("tool.browser.contentTruncated");
        }
        return result;
    }

    public String executeJavaScript(String jsCode) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingEvaluations.put(id, future);

        String base64Code = Base64.getEncoder().encodeToString(jsCode.getBytes(StandardCharsets.UTF_8));
        String wrappedJs = "(function(){" +
                "try{" +
                "var __code=decodeURIComponent(atob('" + base64Code + "').split('').map(function(c){return'%'+('00'+c.charCodeAt(0).toString(16)).slice(-2)}).join(''));" +
                "var __r=eval(__code);" +
                "var __s=(__r===undefined||__r===null)?'null':JSON.stringify(__r);" +
                "window.taiweiBrowserQuery('jsresult:" + id + ":' + __s);" +
                "}catch(e){" +
                "window.taiweiBrowserQuery('jserror:" + id + ":' + e.message);" +
                "}" +
                "})()";

        getOrCreateBrowser().getCefBrowser().executeJavaScript(wrappedJs, "taiwei-eval", 0);

        try {
            // Blocking wait must run off EDT so the JS callback can be delivered.
            return ApplicationManager.getApplication().executeOnPooledThread(
                    (Callable<String>) () -> future.get(JS_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ).get(JS_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingEvaluations.remove(id);
            return ToolError.of("BROWSER_SCRIPT_FAILED", I18nUtil.getMessage("tool.browser.scriptFailed", e.getMessage()),
                    I18nUtil.getMessage("tool.browser.scriptFailedHint"));
        }
    }

    private String evaluateExpression(String expression) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingEvaluations.put(id, future);

        String wrappedJs = "(function(){" +
                "try{" +
                "var __r=" + expression + ";" +
                "var __s=(__r===undefined||__r===null)?'null':JSON.stringify(__r);" +
                "window.taiweiBrowserQuery('jsresult:" + id + ":' + __s);" +
                "}catch(e){" +
                "window.taiweiBrowserQuery('jserror:" + id + ":' + e.message);" +
                "}" +
                "})()";

        getOrCreateBrowser().getCefBrowser().executeJavaScript(wrappedJs, "taiwei-eval", 0);

        try {
            return ApplicationManager.getApplication().executeOnPooledThread(
                    (Callable<String>) () -> future.get(JS_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ).get(JS_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingEvaluations.remove(id);
            return ToolError.of("BROWSER_CONTENT_FAILED", I18nUtil.getMessage("tool.browser.contentFailed", e.getMessage()),
                    I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    public String captureNetwork() {
        JBCefBrowser b = getOrCreateBrowser();
        networkCaptureEnabled = true;
        b.getCefBrowser().executeJavaScript(getNetworkCaptureScript(), "taiwei-netcap", 0);

        List<String> snapshot = new ArrayList<>(capturedRequests);
        if (snapshot.isEmpty()) {
            return I18nUtil.getMessage("tool.browser.captureReady");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18nUtil.getMessage("tool.browser.captured", snapshot.size())).append("\n");
        for (String req : snapshot) {
            sb.append(req).append("\n");
        }
        return sb.toString();
    }

    private String getNetworkCaptureScript() {
        return """
                (function(){
                if(window.__taiweiNetCap)return;
                window.__taiweiNetCap=true;
                var origFetch=window.fetch;
                if(origFetch){
                window.fetch=function(){
                var info={type:'fetch',method:'GET',url:'',time:Date.now()};
                if(typeof arguments[0]==='string')info.url=arguments[0];
                else if(arguments[0]instanceof Request)info.url=arguments[0].url;
                if(arguments[1]){
                if(arguments[1].method)info.method=arguments[1].method;
                }
                return origFetch.apply(this,arguments).then(function(resp){
                info.status=resp.status;
                info.statusText=resp.statusText;
                if(window.taiweiBrowserQuery){
                window.taiweiBrowserQuery('netcap:'+JSON.stringify(info));
                }
                return resp;
                }).catch(function(err){
                info.error=err.message;
                if(window.taiweiBrowserQuery){
                window.taiweiBrowserQuery('netcap:'+JSON.stringify(info));
                }
                throw err;
                });
                };
                }
                var origOpen=XMLHttpRequest.prototype.open;
                var origSend=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(method,url){
                this.__taiweiInfo={type:'xhr',method:method,url:String(url),time:Date.now()};
                return origOpen.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send=function(body){
                if(this.__taiweiInfo){
                var info=this.__taiweiInfo;
                if(body)info.body=String(body).substring(0,2000);
                this.addEventListener('loadend',function(){
                info.status=this.status;
                info.statusText=this.statusText;
                if(window.taiweiBrowserQuery){
                window.taiweiBrowserQuery('netcap:'+JSON.stringify(info));
                }
                });
                }
                return origSend.apply(this,arguments);
                };
                })()
                """;
    }

    @Override
    public synchronized void dispose() {
        if (jsQuery != null) {
            Disposer.dispose(jsQuery);
            jsQuery = null;
        }
        if (browser != null) {
            Disposer.dispose(browser);
            browser = null;
        }
        // Any evaluation still waiting on the (now gone) browser can never complete.
        for (CompletableFuture<String> future : pendingEvaluations.values()) {
            future.completeExceptionally(new IllegalStateException("Browser disposed"));
        }
        pendingEvaluations.clear();
    }
}
