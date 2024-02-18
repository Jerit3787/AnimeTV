package com.amarullz.androidtv.animetvjmto;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.SSLCertificateSocketFactory;
import android.net.Uri;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.chromium.net.CronetEngine;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class AnimeApi extends WebViewClient {
  private static final String _TAG="ATVLOG-API";
  private static final boolean _USECRONET=false;

  public static class Result{
    public String Text;
    public int status;
    public String url;
  }
  public interface Callback {
    void onFinish(Result result);
  }
  private final Activity activity;
  public final WebView webView;
  private final CronetEngine cronet;
  public WebResourceResponse badRequest;

  public boolean paused=false;

  public Callback callback;

  public Result resData=new Result();

  Handler handler = new Handler(Looper.getMainLooper());
  Runnable timeoutRunnable = new Runnable() {
    @Override
    public void run() {
      if (resData.status==1) {
        resData.status = 3;
        resData.Text = "{\"status\":false}";
        activity.runOnUiThread(() -> {
          if (callback!=null)
            callback.onFinish(resData);
        });
      }
    }
  };

  /* Cronet 9anime builder */
  public static CronetEngine buildCronet(Context c){
    /* Setup Cronet HTTP+QUIC Client */
    if (_USECRONET) {
      try {
        CronetEngine.Builder myBuilder =
            new CronetEngine.Builder(c)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 819200)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .enablePublicKeyPinningBypassForLocalTrustAnchors(false)
                    .addQuicHint(Conf.SOURCE_DOMAIN1, 443, 443)
                .addQuicHint(Conf.STREAM_DOMAIN2, 443, 443);
        return myBuilder.build();
      }catch(Exception ignored){}
    }
    return null;
  }

  /* Cronet init quic */
  public static HttpURLConnection initCronetQuic(CronetEngine c, String url, String method) throws IOException {
    HttpURLConnection conn;
    if (c!=null){
      conn = (HttpURLConnection) c.openConnection(new URL(url));
    }
    else{
      URL netConn = new URL(url);
      conn = (HttpURLConnection) netConn.openConnection();
    }
    conn.setRequestMethod(method);
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    conn.setDoOutput(false);
    conn.setDoInput(true);
    return conn;
  }

  /* http get body */
  public static ByteArrayOutputStream getBody(HttpURLConnection conn,
                                              ByteArrayOutputStream buffer,
                                              boolean withErrorBody) throws IOException {
    if (buffer == null) buffer = new ByteArrayOutputStream();
    InputStream is;
    if (withErrorBody) {
      if (conn.getResponseCode() == 200)
        is = conn.getInputStream();
      else
        is = conn.getErrorStream();
    }
    else{
      is=conn.getInputStream();
    }
    try {
      int nRead;
      byte[] data = new byte[1024];
      while ((nRead = is.read(data, 0, data.length)) != -1) {
        buffer.write(data, 0, nRead);
      }
    }catch (Exception ignored){}
    return buffer;
  }
  public static ByteArrayOutputStream getBody(HttpURLConnection conn,
                                              ByteArrayOutputStream buffer) throws IOException {
    return getBody(conn,buffer,false);
  }

  public void updateServerVar(boolean showMessage){
    AsyncTask.execute(() -> {
      try {
        File fp = new File(apkTempFile());
        if (fp.delete()) {
          Log.d(_TAG, "TEMP APK FILE DELETED");
        }
        else{
          Log.d(_TAG, "NO TEMP APK FILE");
        }
      }catch(Exception ignored){
      }

      try {
        /* Get Server Data from Github */
        HttpURLConnection conn = initCronetQuic(
            null,
            "https://raw.githubusercontent.com/amarullz/AnimeTV/master/server" +
                ".json?"+System.currentTimeMillis(),"GET"
        );
        ByteArrayOutputStream buffer = AnimeApi.getBody(conn, null);
        String serverjson=buffer.toString();
        JSONObject j=new JSONObject(serverjson);
        String update=j.getString("update");
        if (!Conf.SERVER_VER.equals(update)){
          /* Updated */
          Log.d(_TAG,"SERVER-UPDATED: "+serverjson);
          SharedPreferences.Editor ed=pref.edit();
          ed.putString("server-json",serverjson);
          ed.apply();
          initPref();
        }
        else{
          Log.d(_TAG,"SERVER UP TO DATE");
        }

        // BuildConfig.VERSION_CODE
        int appnum=j.getInt("appnum");
        if (appnum>BuildConfig.VERSION_CODE){
          Log.d(_TAG,"NEW APK VERSION AVAILABLE");
          String appurl=j.getString("appurl");
          String appver=j.getString("appver");
          String appnote=j.getString("appnote");
          String appsize=j.getString("appsize");
          Log.d(_TAG,
              "showUpdateDialog = "+appver+" / "+appsize+" / "+appurl+" / "+
                  appnote);
          boolean updateState=pref.getBoolean("update-disable",false);

          if (!updateState || showMessage) {
            activity.runOnUiThread(() -> showUpdateDialog(appurl, appver,
                appnote, appsize));
          }
          else{
            activity.runOnUiThread(() ->
              Toast.makeText(activity,
                  "Update version "+appver+" is available...",
                  Toast.LENGTH_SHORT).show()
            );
          }
        }
        else{
          if (showMessage){
            activity.runOnUiThread(() ->
              Toast.makeText(activity,
                  "AnimeTV already up to date...",
                  Toast.LENGTH_SHORT).show()
            );
          }
          Log.d(_TAG,"APP UP TO DATE");
        }
      }catch(Exception ignored){}
    });
  }

  private String apkTempFile(){
    return activity.getFilesDir() + "/update.apk";
  }
  private void installApk(File apkfile) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() +
              ".provider",apkfile);
    List<ResolveInfo> resInfoList =
        activity.getPackageManager().queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY);
    for (ResolveInfo resolveInfo : resInfoList) {
      String packageName = resolveInfo.activityInfo.packageName;
      activity.grantUriPermission(packageName, uri,
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    Intent intentApp = new Intent(Intent.ACTION_INSTALL_PACKAGE);
    intentApp.setData(uri);
    Log.d(_TAG,"INSTALLING APK = "+uri);
    intentApp.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    activity.startActivity(intentApp);
  }
  private void startUpdateApk(String url){
    AsyncTask.execute(() -> {
      try {
        Log.d(_TAG,"DOWNLOADING APK = "+url);
        HttpURLConnection conn = initCronetQuic(
            null,
            url,"GET"
        );

        ByteArrayOutputStream buffer = AnimeApi.getBody(conn, null);
        Log.d(_TAG,"DOWNLOADED APK = "+buffer.size());
        activity.runOnUiThread(() ->
          Toast.makeText(activity,
              "Update has been downloaded ("+((buffer.size()/1024)/1024)+"MB)",
              Toast.LENGTH_SHORT).show()
        );
        String apkpath=apkTempFile();
        FileOutputStream fos = new FileOutputStream(apkpath);
        buffer.writeTo(fos);
        File fp=new File(apkpath);
        installApk(fp);
      }catch(Exception er){
        activity.runOnUiThread(() ->
          Toast.makeText(activity,"Update Failed: "+er,
              Toast.LENGTH_SHORT).show()
        );
        Log.d(_TAG,"DOWNLOAD ERR = "+er);
      }
    });
  }

  private void showUpdateDialog(String url, String ver, String changelog,
                                String sz){

    new AlertDialog.Builder(activity)
        .setTitle("Update Available - Version "+ver)
        .setMessage("Download Size : "+sz+"\n\nChangelogs:\n"+changelog)
        .setNegativeButton("Later", (dialogInterface, i) -> {
          SharedPreferences.Editor ed=pref.edit();
          ed.putBoolean("update-disable",false);
          ed.apply();
        })
        .setNeutralButton("Don't Remind Me", (dialogInterface, i) -> {
          SharedPreferences.Editor ed=pref.edit();
          ed.putBoolean("update-disable",true);
          ed.apply();
        })
        .setPositiveButton("Update Now", (dialog, which) -> {
          Toast.makeText(activity,"Downloading Update...",
              Toast.LENGTH_SHORT).show();
          startUpdateApk(url);
        }).show();
  }

  public SharedPreferences pref;
  public String prefServer="";
  public void initPref(){
    prefServer=pref.getString("server-json","");
    if (!prefServer.equals("")){
      try {
        JSONObject j=new JSONObject(prefServer);
        Conf.SOURCE_DOMAIN1=j.getString("domain");
        Conf.STREAM_DOMAIN=j.getString("stream_domain");
        Conf.SERVER_VER=j.getString("update");
        Conf.STREAM_DOMAIN2=j.getString("stream_domain2");
        Conf.SOURCE_DOMAIN2=j.getString("domain2");
      }catch(Exception ignored){}
    }
    Conf.SOURCE_DOMAIN=pref.getInt("source-domain",Conf.SOURCE_DOMAIN);
    Conf.updateSource(Conf.SOURCE_DOMAIN);
    Log.d(_TAG,"DOMAIN = "+Conf.getDomain()+" / STREAM = "+Conf.STREAM_DOMAIN+" / " +
        "UPDATE = "+Conf.SERVER_VER+" / Source-ID: "+Conf.SOURCE_DOMAIN);
  }

  public void setSourceDomain(int i){
    if (i>=1 && i<=2) {
      SharedPreferences.Editor ed = pref.edit();
      ed.putInt("source-domain", i);
      ed.apply();
      Conf.updateSource(i);
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  public AnimeApi(Activity mainActivity) {
    activity = mainActivity;

    /* Update Server */
    updateServerVar(false);

    webView = new WebView(activity);
//    webView = activity.findViewById(R.id.webview);

    pref = activity.getSharedPreferences("SERVER", Context.MODE_PRIVATE );
    initPref();
    cronet = buildCronet(activity);

    /* Init Bad Request */
    badRequest = new WebResourceResponse("text/plain",
        null, 400, "Bad " +
        "Request", null, null);



    /* Init Webview */
    webView.setBackgroundColor(0xffffffff);
    WebSettings webSettings = webView.getSettings();
    webSettings.setJavaScriptEnabled(true);
    webSettings.setMediaPlaybackRequiresUserGesture(false);
    webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      webSettings.setSafeBrowsingEnabled(true);
    }
    webSettings.setSupportMultipleWindows(false);
    webSettings.setBlockNetworkImage(true);
    webView.addJavascriptInterface(new JSApi(), "_JSAPI");
    webView.setWebViewClient(this);

    webSettings.setUserAgentString(Conf.USER_AGENT);
    webView.loadData(
          "<html><body>Finish</body></html>","text/html",
          null
      );
  }

  public void getData(String url, Callback cb, long timeout){
    if (resData.status==1) return;
    callback=cb;
    pauseView(false);
    resData.url=url;
    resData.status=1;
    handler.postDelayed(timeoutRunnable, timeout);
    webView.evaluateJavascript("(window.__EPGET&&window.__EPGET('"+url+"'))" +
            "?1:0",
        s -> {
          Log.d(_TAG,"JAVASCRIPT VAL ["+s+"]");
          if (s.equals("0")){
            webView.loadUrl(url);
          }
        });
  }
  public void getData(String url, Callback cb){
    getData(url,cb,20000);
  }



  public HttpURLConnection initQuic(String url, String method) throws IOException {
    return initCronetQuic(cronet,url,method);
  }



  public void injectString(ByteArrayOutputStream buffer, String inject){
    byte[] injectByte = inject.getBytes();
    buffer.write(injectByte, 0, injectByte.length);
  }

  public void injectJs(ByteArrayOutputStream buffer, String url){
    injectString(buffer, "<script src=\""+url+
        "\"></script>");
  }

  public String getMimeFn(String fn) {
    int extpos=fn.lastIndexOf(".");
    if (extpos>=0) {
      String ex = fn.substring(extpos);
      switch (ex) {
        case ".css":
          return "text/css";
        case ".js":
          return "application/javascript";
        case ".png":
          return "image/png";
        case ".jpg":
          return "image/jpeg";
        case ".html":
          return "text/html";
      }
    }
    return "text/plain";
  }

  public WebResourceResponse assetsRequest(String fn){
    try {
      Log.d(_TAG, "ASSETS="+fn);
      String mime = getMimeFn(fn);
      InputStream is = activity.getAssets().open(fn);
      return new WebResourceResponse(mime,
          null, 200, "OK",
          null, is);
    } catch (IOException ignored) {}
    return badRequest;
  }

  public String assetsString(String fn){
    try {
      StringBuilder sb = new StringBuilder();
      InputStream is = activity.getAssets().open(fn);
      BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8 ));
      String str;
      while ((str = br.readLine()) != null) {
        sb.append(str);
      }
      str=sb.toString();
      br.close();
      return str;
    } catch (IOException ignored) {}
    return "";
  }

  public String[] parseContentType(String contentType) {
    String[] ret = new String[2];
    ret[0] = "application/octet-stream";
    ret[1] = null;
    if (contentType != null) {
      String[] ctype = contentType.split(";");
      ret[0] = ctype[0].trim();
      if (ctype.length == 2) {
        ret[1] = ctype[1].split("=")[1].trim();
      }
    }
    return ret;
  }

  @SuppressLint("WebViewClientOnReceivedSslError")
  @Override
  public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                 SslError error) {
    handler.proceed();
  }

  @Override
  public void onPageFinished(WebView view, String url) {
    // Make sure inject.js is attached
    String ijs="(function(){var a=document.createElement('script');a" +
        ".setAttribute('src','/__inject.js');document.body.appendChild(a);})" +
        "();";
    webView.evaluateJavascript(ijs,null);
  }

  @Override
  public WebResourceResponse shouldInterceptRequest(final WebView view,
                                                    WebResourceRequest request) {
    Uri uri = request.getUrl();
    String url = uri.toString();
    String host = uri.getHost();
    String accept = request.getRequestHeaders().get("Accept");
    if (accept==null) return badRequest;
    if (host==null) return badRequest;
    if (url.startsWith("data:")) {
      return super.shouldInterceptRequest(view, request);
    }
    else if (accept.startsWith("image/")) return badRequest;
    else if (host.contains(Conf.SOURCE_DOMAIN1)) {
      if (Objects.equals(uri.getPath(), "/__inject.js")){
        Log.d(_TAG, "WEB-REQ-ASSETS=" + url);
        return assetsRequest("inject/9anime_inject.js");
      }
      return defaultRequest(view, request, "/__inject.js", "text/html");
    }
    else if (host.contains(Conf.SOURCE_DOMAIN2)) {
      if (Objects.equals(uri.getPath(), "/__inject.js")) {
        Log.d(_TAG, "WEB-REQ-ASSETS=" + url);
        return assetsRequest("inject/anix_inject.js");
      }
      return defaultRequest(view, request, "/__inject.js", "text/html");
    }
    else if (host.contains(Conf.STREAM_DOMAIN)||host.contains(Conf.STREAM_DOMAIN2)){
      return assetsRequest("inject/9anime_player.html");
    }
    else if (host.contains("cloudflare.com")||
        host.contains("bunnycdn.ru")) {
      if (!url.endsWith(".woff2")&&!accept.startsWith("text/css")) {
        Log.d(_TAG, "CDN=>" + url + " - " + accept);
        if (Conf.PROGRESSIVE_CACHE){
          return super.shouldInterceptRequest(view,request);
        }
        return defaultRequest(view, request);
      }
    }
    return badRequest;
  }

  /* Default Fallback HTTP Request */
  public WebResourceResponse defaultRequest(final WebView view,
                                                    WebResourceRequest request,
                                            String inject, String injectContentType) {
    Uri uri = request.getUrl();
    String url = uri.toString();
    final String host = uri.getHost();
    boolean dnss=false;
    try {
      HttpsURLConnection conn;
      if (host.equals("aniwave.to")||host.equals("anix.to")){
        String ipx=host.equals("aniwave.to")?"172.67.132.150":"172.67.164.135";
        conn =(HttpsURLConnection) initQuic(url.replaceFirst(host, ipx), request.getMethod());
        conn.setHostnameVerifier(new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
            //return true;
        }
        });
        TlsSniSocketFactory sslSocketFactory = new TlsSniSocketFactory(conn);
        conn.setSSLSocketFactory(sslSocketFactory);
       
        dnss=true;
      }
      else{
        conn =(HttpsURLConnection) initQuic(url, request.getMethod());
      }
      for (Map.Entry<String, String> entry :
        request.getRequestHeaders().entrySet()) {
        conn.setRequestProperty(entry.getKey(), entry.getValue());
      }
      conn.setRequestProperty("Host", host);
      conn.setInstanceFollowRedirects(false);
      
      conn.connect();

      String[] cType = parseContentType(conn.getContentType());
      ByteArrayOutputStream buffer = getBody(conn, null);

      // Inject
      if (inject!=null) {
        if (injectContentType==null){
          injectContentType="text/html";
        }
        if (cType[0].startsWith(injectContentType)) {
          injectJs(buffer, inject);
        }
      }

      InputStream stream = new ByteArrayInputStream(buffer.toByteArray());
      if (dnss) Log.d(_TAG,"DNSS = "+url);
      return new WebResourceResponse(cType[0], cType[1], stream);
    } catch (Exception e) {
      Log.e(_TAG, "HTTP-DEF-REQ-ERR=" + url, e);
    }
    return null;
  }
  public WebResourceResponse defaultRequest(final WebView view,
                                            WebResourceRequest request){
    return defaultRequest(view,request,null,null);
  }

  public String getMp4Video(String url){
    String srcjson = "null";
    try {
      HttpURLConnection conn = initQuic(url, "GET");
      ByteArrayOutputStream buffer = getBody(conn, null);
      String mp4src = buffer.toString();
      int psrcpos = mp4src.indexOf("player.src(");
      if (psrcpos > 0) {
        srcjson = mp4src.substring(psrcpos + 11);
        srcjson = srcjson.substring(0, srcjson.indexOf(");"));
      }
    }catch(Exception ignored){}
    return srcjson;
  }

  @Override
  public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
    String url = request.getUrl().toString();
    return (!url.startsWith("https://"+Conf.SOURCE_DOMAIN1+"/")&&!url.startsWith(
        "https://"+Conf.SOURCE_DOMAIN2+"/"));
  }

  public void pauseView(boolean pause){
    activity.runOnUiThread(() -> {
      if (pause) {
        if (!paused) {
          paused= true;
          webView.onPause();
        }
      }
      else if (paused) {
        paused= false;
        webView.onResume();
      }
    });
  }

  public void cleanWebView(){
    callback=null;
    handler.removeCallbacks(timeoutRunnable);
    resData.status = 2;
    activity.runOnUiThread(() -> {
      pauseView(false);
      webView.loadData(
          "<html><body>Finish</body></html>","text/html",
          null
      );
      pauseView(true);
    });
  }

  public class JSApi{
    @JavascriptInterface
    public void result(String res) {
      if (resData.status==1) {
        handler.removeCallbacks(timeoutRunnable);
        resData.status = 2;
        resData.Text = res;
        Log.d(_TAG,"GETVIEW: "+res);
        activity.runOnUiThread(() -> {
          if (callback!=null)
            callback.onFinish(resData);
        });
        pauseView(true);
      }
    }

    @JavascriptInterface
    public int streamType(){
      return Conf.STREAM_TYPE;
    }

    @JavascriptInterface
    public int streamServer(){
      return Conf.STREAM_SERVER;
    }
    @JavascriptInterface
    public String dns(){
      return Conf.getDomain();
    }

    @JavascriptInterface
    public String dnsver(){
      return Conf.SERVER_VER;
    }
  }
  
  
  class TlsSniSocketFactory extends SSLSocketFactory {
    private final String TAG = TlsSniSocketFactory.class.getSimpleName();
    HostnameVerifier hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    private HttpsURLConnection conn;

    public TlsSniSocketFactory(HttpsURLConnection conn) {
        this.conn = conn;
    }

    @Override
    public Socket createSocket() throws IOException {
        return null;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return null;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
        return null;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return null;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return null;
    }

    // TLS layer

    @Override
    public String[] getDefaultCipherSuites() {
        return new String[0];
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return new String[0];
    }

    @Override
    public Socket createSocket(Socket plainSocket, String host, int port, boolean autoClose) throws IOException {
        String peerHost = this.conn.getRequestProperty("Host");
        if (peerHost == null)
            peerHost = host;
        Log.i(TAG, "customized createSocket. host: " + peerHost);
        InetAddress address = plainSocket.getInetAddress();
        if (autoClose) {
            // we don't need the plainSocket
            plainSocket.close();
        }
        // create and connect SSL socket, but don't do hostname/certificate verification yet
        SSLCertificateSocketFactory sslSocketFactory = (SSLCertificateSocketFactory) SSLCertificateSocketFactory.getDefault(0);
        SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(address, port);

        // enable TLSv1.1/1.2 if available
        ssl.setEnabledProtocols(ssl.getSupportedProtocols());

        // set up SNI before the handshake
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Log.i(TAG, "Setting SNI hostname");
            sslSocketFactory.setHostname(ssl, peerHost);
        } else {
            Log.d(TAG, "No documented SNI support on Android <4.2, trying with reflection");
            try {
                java.lang.reflect.Method setHostnameMethod = ssl.getClass().getMethod("setHostname", String.class);
                setHostnameMethod.invoke(ssl, peerHost);
            } catch (Exception e) {
                Log.w(TAG, "SNI not useable", e);
            }
        }

        // verify hostname and certificate
        SSLSession session = ssl.getSession();

        if (!hostnameVerifier.verify(peerHost, session))
            throw new SSLPeerUnverifiedException("Cannot verify hostname: " + peerHost);

        Log.i(TAG, "Established " + session.getProtocol() + " connection with " + session.getPeerHost() +
                " using " + session.getCipherSuite());

        return ssl;
    }
}
}
