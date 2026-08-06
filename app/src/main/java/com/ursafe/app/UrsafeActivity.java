package com.ursafe.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class UrsafeActivity extends Activity {
    public static final String ACTION_TERMUX_RESULT="com.ursafe.app.TERMUX_RESULT_V05";
    private static final String CHAT="https://chatgpt.com/", LOGIN="https://chatgpt.com/auth/login";
    private static final String TERMUX="com.termux", PERMISSION="com.termux.permission.RUN_COMMAND";
    private static final int REQ_PERMISSION=501;
    private static final AtomicInteger IDS=new AtomicInteger(3000);

    private enum Page { HOME, CHAT, TERMUX }
    private FrameLayout host;
    private LinearLayout nav;
    private Page page=Page.HOME;
    private WebView web;
    private ProgressBar progress;
    private TextView webTitle, status, console;
    private EditText command;
    private Button share, copy;
    private String pendingText, lastCommand="", lastOut="", lastErr="", lastMessage="";
    private int lastExit=-1;
    private boolean registered;

    private final BroadcastReceiver results=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            lastCommand=s(i.getStringExtra("command"));
            lastOut=s(i.getStringExtra("stdout"));
            lastErr=s(i.getStringExtra("stderr"));
            lastMessage=s(i.getStringExtra("message"));
            lastExit=i.getIntExtra("exit_code",-1);
            setStatus(lastMessage);
            if(console!=null) console.setText(formatResult());
            updateResultButtons();
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(247,248,252));
        getWindow().setNavigationBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildShell(); showHome();
    }
    @Override protected void onStart(){
        super.onStart(); IntentFilter f=new IntentFilter(ACTION_TERMUX_RESULT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(results,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(results,f);
        registered=true; refreshStatus();
    }
    @Override protected void onStop(){if(registered){unregisterReceiver(results);registered=false;}super.onStop();}
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
    @Override public void onBackPressed(){if(page!=Page.HOME){showHome();return;}super.onBackPressed();}

    private void buildShell(){
        LinearLayout root=vbox(); root.setBackgroundColor(Color.rgb(247,248,252)); setContentView(root);
        host=new FrameLayout(this); root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        nav=hbox(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(6),dp(5),dp(6),dp(7)); nav.setBackgroundColor(Color.WHITE); nav.setElevation(dp(12));
        root.addView(nav,new LinearLayout.LayoutParams(-1,dp(66)));
        nav.addView(navButton("⌂\nმთავარი",Page.HOME),weight());
        nav.addView(navButton("✦\nჩატი",Page.CHAT),weight());
        nav.addView(navButton(">_\nTermux",Page.TERMUX),weight());
    }
    private Button navButton(String label,Page target){
        Button b=button(label,12); b.setTag(target); b.setMinHeight(0); b.setPadding(2,0,2,0); b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(v->{if(target==Page.HOME)showHome();else if(target==Page.CHAT)showChat(CHAT);else showTermux();}); return b;
    }
    private void selectNav(){
        for(int n=0;n<nav.getChildCount();n++){Button b=(Button)nav.getChildAt(n);boolean on=b.getTag()==page;b.setTextColor(on?purple():Color.rgb(108,112,128));b.setTypeface(Typeface.DEFAULT,on?Typeface.BOLD:Typeface.NORMAL);}
    }

    private void showHome(){
        page=Page.HOME;selectNav();host.removeAllViews();
        ScrollView scroll=new ScrollView(this);host.addView(scroll,frameMatch());
        LinearLayout body=vbox();body.setPadding(dp(20),dp(18),dp(20),dp(26));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        body.addView(brand(),match());body.addView(gap(18));
        LinearLayout hero=card(Color.WHITE,26);hero.setPadding(dp(22),dp(23),dp(22),dp(22));hero.setElevation(dp(3));body.addView(hero,match());
        TextView tag=txt("URSAFE • PLUS SHELL",12,true,purple());tag.setLetterSpacing(.12f);hero.addView(tag,match());
        TextView title=txt("ChatGPT და Termux,\nერთ სივრცეში",30,true,Color.rgb(22,24,32));title.setPadding(0,dp(9),0,dp(10));hero.addView(title,match());
        TextView info=txt("WebView რჩება Plus ანგარიშისა და არსებული ჩატებისთვის. ტელეფონის Back ღილაკი ახლა პირდაპირ Ursafe-ის მთავარ გვერდზე დაგაბრუნებს.",15,false,Color.rgb(92,96,111));info.setLineSpacing(dp(2),1.1f);hero.addView(info,match());
        hero.addView(gap(18));Button open=primary("ChatGPT-ის გახსნა");open.setOnClickListener(v->showChat(CHAT));hero.addView(open,match());
        hero.addView(gap(9));Button login=secondary("ანგარიშზე შესვლა");login.setOnClickListener(v->showChat(LOGIN));hero.addView(login,match());
        body.addView(gap(17));
        LinearLayout bridge=card(Color.rgb(30,33,44),24);bridge.setPadding(dp(20),dp(20),dp(20),dp(20));bridge.setElevation(dp(2));body.addView(bridge,match());
        bridge.addView(txt(">_  Termux bridge",22,true,Color.WHITE),match());
        TextView bc=txt("ბრძანება სრულდება მხოლოდ შენი დადასტურებით. stdout/stderr ბრუნდება Ursafe-ში და ერთი ღილაკით მზადდება ChatGPT-ის ტექსტის ველში.",14,false,Color.rgb(201,204,216));bc.setPadding(0,dp(12),0,dp(14));bc.setLineSpacing(dp(2),1.1f);bridge.addView(bc,match());
        Button term=dark("Termux კონსოლის გახსნა");term.setOnClickListener(v->showTermux());bridge.addView(term,match());
        status=txt("სტატუსი იტვირთება…",13,false,Color.rgb(211,214,226));status.setPadding(dp(13),dp(11),dp(13),dp(11));status.setBackground(round(Color.rgb(42,46,59),14));bridge.addView(gap(11));bridge.addView(status,match());
        TextView note=txt("Ursafe არ კითხულობს ჩატის პასუხებს. Termux შედეგი ჩატში მხოლოდ შენს ღილაკზე დაჭერით გადადის და გაგზავნასაც თავად ადასტურებ.",12,false,Color.rgb(124,128,143));note.setPadding(dp(3),dp(17),dp(3),0);body.addView(note,match());
        refreshStatus();
    }

    private void showChat(String url){
        page=Page.CHAT;selectNav();host.removeAllViews();LinearLayout shell=vbox();shell.setBackgroundColor(Color.WHITE);host.addView(shell,frameMatch());
        LinearLayout bar=hbox();bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(6),dp(4),dp(6),dp(4));bar.setBackgroundColor(Color.WHITE);bar.setElevation(dp(3));shell.addView(bar,new LinearLayout.LayoutParams(-1,dp(56)));
        Button back=tool("‹");back.setOnClickListener(v->{if(web!=null&&web.canGoBack())web.goBack();else showHome();});bar.addView(back,new LinearLayout.LayoutParams(dp(43),dp(43)));
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_ursafe_logo);bar.addView(logo,new LinearLayout.LayoutParams(dp(32),dp(32)));
        webTitle=txt("Ursafe Chat",16,true,Color.rgb(25,27,36));webTitle.setSingleLine();webTitle.setPadding(dp(9),0,dp(6),0);bar.addView(webTitle,weight());
        Button reload=tool("↻");reload.setOnClickListener(v->{if(web!=null)web.reload();});bar.addView(reload,new LinearLayout.LayoutParams(dp(43),dp(43)));
        Button home=tool("⌂");home.setOnClickListener(v->showHome());bar.addView(home,new LinearLayout.LayoutParams(dp(43),dp(43)));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);shell.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        ensureWeb(); detach(web);shell.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        String current=web.getUrl();if(current==null||current.isEmpty()||!CHAT.equals(url))web.loadUrl(url);else if(pendingText!=null)injectPending();
    }
    private void ensureWeb(){
        if(web!=null)return;web=new WebView(this);WebSettings w=web.getSettings();w.setJavaScriptEnabled(true);w.setDomStorageEnabled(true);w.setDatabaseEnabled(true);w.setAllowFileAccess(false);w.setAllowContentAccess(false);w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(web,true);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){if(progress!=null){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}}
            @Override public void onReceivedTitle(WebView v,String t){if(webTitle!=null&&t!=null&&!t.trim().isEmpty())webTitle.setText(t.length()>32?t.substring(0,32)+"…":t);}
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String scheme=s(r.getUrl().getScheme()).toLowerCase(Locale.ROOT);if("http".equals(scheme)||"https".equals(scheme))return false;try{startActivity(new Intent(Intent.ACTION_VIEW,r.getUrl()));}catch(Exception e){toast("ბმული ვერ გაიხსნა.");}return true;}
            @Override public void onPageFinished(WebView v,String u){CookieManager.getInstance().flush();if(pendingText!=null)injectPending();}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler h,SslError e){h.cancel();toast("უსაფრთხო კავშირის შეცდომა.");}
        });
    }

    private void showTermux(){
        page=Page.TERMUX;selectNav();host.removeAllViews();ScrollView scroll=new ScrollView(this);host.addView(scroll,frameMatch());
        LinearLayout body=vbox();body.setPadding(dp(18),dp(16),dp(18),dp(24));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        body.addView(txt("Termux bridge",27,true,Color.rgb(23,25,34)),match());body.addView(txt("დადასტურებადი ბრძანებები • stdout/stderr • ChatGPT bridge",12,false,Color.rgb(109,113,129)),match());body.addView(gap(16));
        LinearLayout input=card(Color.WHITE,21);input.setPadding(dp(17),dp(17),dp(17),dp(17));input.setElevation(dp(2));body.addView(input,match());input.addView(txt("ბრძანება",15,true,Color.rgb(31,33,43)),match());
        command=new EditText(this);command.setHint("მაგ: pwd  ან  ls -la");command.setTextSize(15);command.setMinLines(2);command.setMaxLines(5);command.setPadding(dp(13),dp(11),dp(13),dp(11));command.setBackground(stroke(Color.rgb(248,248,252),Color.rgb(220,219,232),14));input.addView(gap(9));input.addView(command,match());
        LinearLayout acts=hbox();input.addView(gap(11));input.addView(acts,match());Button run=primary("გაშვება");run.setOnClickListener(v->confirmCommand());acts.addView(run,weight());acts.addView(hgap(9));Button test=secondary("ტესტი");test.setOnClickListener(v->runCommand("printf 'Ursafe ↔ Termux OK\\n'; pwd","test"));acts.addView(test,weight());
        Button perm=link("ნებართვა / სისტემური პარამეტრები");perm.setOnClickListener(v->requestPermission());input.addView(gap(7));input.addView(perm,match());
        status=txt("სტატუსი იტვირთება…",13,false,Color.rgb(91,95,110));status.setPadding(dp(13),dp(11),dp(13),dp(11));status.setBackground(round(Color.rgb(241,241,247),14));input.addView(status,match());
        body.addView(gap(15));LinearLayout out=card(Color.rgb(27,29,38),21);out.setPadding(dp(15),dp(15),dp(15),dp(15));out.setElevation(dp(2));body.addView(out,match());
        LinearLayout oh=hbox();oh.setGravity(Gravity.CENTER_VERTICAL);out.addView(oh,match());oh.addView(txt("კონსოლი",17,true,Color.WHITE),weight());Button clear=smallDark("გასუფთავება");clear.setOnClickListener(v->{lastCommand=lastOut=lastErr=lastMessage="";lastExit=-1;if(console!=null)console.setText("$ Ursafe console ready\n");updateResultButtons();});oh.addView(clear,wrap());
        console=txt(lastMessage.isEmpty()?"$ Ursafe console ready\n":formatResult(),13,false,Color.rgb(210,214,226));console.setTypeface(Typeface.MONOSPACE);console.setTextIsSelectable(true);console.setMinHeight(dp(190));console.setPadding(dp(12),dp(13),dp(12),dp(13));console.setBackground(round(Color.rgb(36,39,50),14));out.addView(gap(9));out.addView(console,match());
        LinearLayout ra=hbox();out.addView(gap(11));out.addView(ra,match());copy=dark("კოპირება");copy.setOnClickListener(v->copyResult());ra.addView(copy,weight());ra.addView(hgap(9));share=dark("ჩატში მომზადება");share.setOnClickListener(v->prepareChat());ra.addView(share,weight());
        TextView n=txt("შედეგი მხოლოდ ტექსტის ველში ჩაისმება. ChatGPT-ში გაგზავნის ღილაკს შენ აჭერ.",12,false,Color.rgb(174,178,192));n.setPadding(0,dp(10),0,0);out.addView(n,match());refreshStatus();updateResultButtons();
    }

    private void confirmCommand(){
        String cmd=command==null?"":command.getText().toString().trim(),error=policy(cmd);if(error!=null){new AlertDialog.Builder(this).setTitle("ბრძანება დაბლოკილია").setMessage(error).setPositiveButton("გასაგებია",null).show();return;}
        new AlertDialog.Builder(this).setTitle("გაუშვა Termux-ში?").setMessage(cmd+"\n\nშესრულდება შენი Termux მომხმარებლის უფლებებით.").setNegativeButton("გაუქმება",null).setPositiveButton("გაშვება",(d,w)->runCommand(cmd,"user")).show();
    }
    private String policy(String cmd){
        if(cmd.isEmpty())return "შეიყვანე ბრძანება.";if(cmd.length()>700)return "ბრძანება ზედმეტად გრძელია.";String x=cmd.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
        String[] blocked={"sudo ","su ","rm -rf /","rm -fr /","mkfs","mkswap","reboot","shutdown","poweroff","halt","setenforce 0","/dev/block","of=/dev/",":(){:|:&};:"};for(String b:blocked)if(x.contains(b))return "მაღალი რისკის ბრძანება დაბლოკილია: "+b;
        if((x.contains("curl ")||x.contains("wget "))&&(x.contains("| sh")||x.contains("| bash")))return "ინტერნეტიდან მიღებული სკრიპტის პირდაპირ shell-ში გაშვება დაბლოკილია.";return null;
    }
    private void runCommand(String cmd,String kind){
        if(!termuxInstalled()){setStatus("Termux ვერ მოიძებნა.");return;}if(checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED){setStatus("ჯერ მიანიჭე RUN_COMMAND ნებართვა.");requestPermission();return;}
        int id=IDS.incrementAndGet();Intent result=new Intent(this,BridgeResultService.class).putExtra("request_id",id).putExtra("request_kind",kind).putExtra("command",cmd);int flags=PendingIntent.FLAG_ONE_SHOT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;PendingIntent pi=PendingIntent.getService(this,id,result,flags);
        Intent run=new Intent();run.setClassName(TERMUX,"com.termux.app.RunCommandService");run.setAction("com.termux.RUN_COMMAND");run.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");run.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-lc",cmd});run.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home");run.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);run.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL","Ursafe command");run.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION","User-confirmed Ursafe command");run.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",pi);
        try{startService(run);lastCommand=cmd;setStatus("Termux-ში გაიგზავნა…");if(console!=null)console.setText("$ "+cmd+"\n[მიმდინარეობს…]\n");}catch(Exception e){setStatus("Termux შეცდომა: "+s(e.getMessage()));}
    }
    private void prepareChat(){
        if(lastMessage.isEmpty()&&lastOut.isEmpty()&&lastErr.isEmpty()){toast("ჯერ მიიღე Termux შედეგი.");return;}pendingText=cut("Ursafe Termux bridge-იდან გიგზავნი შედეგს. გააანალიზე და მითხარი შემდეგი უსაფრთხო ნაბიჯი. თუ ბრძანებას მთავაზობ, დაწერე ცალკე code block-ში და არ ჩათვალო ავტომატურად შესრულებულად.\n\nCOMMAND:\n"+lastCommand+"\n\nEXIT CODE:\n"+lastExit+"\n\nSTDOUT:\n"+(lastOut.isEmpty()?"(ცარიელია)":lastOut)+"\n\nSTDERR:\n"+(lastErr.isEmpty()?"(ცარიელია)":lastErr),12000);showChat(CHAT);
    }
    private void injectPending(){
        if(web==null||pendingText==null)return;String js="(function(){const e=document.querySelector('#prompt-textarea')||document.querySelector('textarea');if(!e)return 'missing';e.focus();const t="+JSONObject.quote(pendingText)+";if('value' in e){e.value=t;e.dispatchEvent(new Event('input',{bubbles:true}));}else{e.textContent=t;e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t}));}return 'ok';})()";
        web.evaluateJavascript(js,v->{if(v!=null&&v.contains("ok")){pendingText=null;toast("Termux შედეგი ჩატის ველში მომზადდა.");}});
    }
    private void copyResult(){if(lastMessage.isEmpty()&&lastOut.isEmpty()&&lastErr.isEmpty()){toast("ჯერ შედეგი არ არის.");return;}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Ursafe result",formatResult()));toast("შედეგი დაკოპირდა.");}
    private String formatResult(){return cut("$ "+lastCommand+"\nexit="+lastExit+"\n"+(lastOut.isEmpty()?"":lastOut.trim()+"\n")+(lastErr.isEmpty()?"":"[stderr]\n"+lastErr.trim()+"\n"),16000);}
    private void updateResultButtons(){boolean ok=!(lastMessage.isEmpty()&&lastOut.isEmpty()&&lastErr.isEmpty());if(share!=null)share.setEnabled(ok);if(copy!=null)copy.setEnabled(ok);}

    private void requestPermission(){if(!termuxInstalled()){setStatus("Termux ვერ მოიძებნა.");return;}if(checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED){setStatus("RUN_COMMAND ნებართვა მინიჭებულია.");return;}requestPermissions(new String[]{PERMISSION},REQ_PERMISSION);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_PERMISSION){boolean ok=g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED;setStatus(ok?"RUN_COMMAND ნებართვა მინიჭებულია.":"ნებართვა არ მინიჭებულა.");if(!ok){Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);}}}
    private boolean termuxInstalled(){try{getPackageManager().getPackageInfo(TERMUX,0);return true;}catch(Exception e){return false;}}
    private void refreshStatus(){setStatus("Termux: "+(termuxInstalled()?"კი":"არა")+" • RUN_COMMAND: "+(checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED?"მინიჭებულია":"არ არის მინიჭებული"));}
    private void setStatus(String x){if(status!=null)status.setText(x);}

    private View brand(){LinearLayout h=hbox();h.setGravity(Gravity.CENTER_VERTICAL);ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_ursafe_logo);h.addView(logo,new LinearLayout.LayoutParams(dp(50),dp(50)));LinearLayout t=vbox();t.setPadding(dp(12),0,0,0);h.addView(t,weight());t.addView(txt("Ursafe",27,true,Color.rgb(22,24,32)),match());t.addView(txt("Plus shell + Termux bridge",13,false,Color.rgb(112,116,132)),match());TextView v=txt("v0.5",12,true,purple());v.setPadding(dp(9),dp(6),dp(9),dp(6));v.setBackground(round(Color.rgb(235,231,255),99));h.addView(v,wrap());return h;}
    private LinearLayout vbox(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}private LinearLayout hbox(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.HORIZONTAL);return x;}
    private LinearLayout card(int c,int r){LinearLayout x=vbox();x.setBackground(round(c,r));return x;}private TextView txt(String x,int z,boolean b,int c){TextView v=new TextView(this);v.setText(x);v.setTextSize(z);v.setTextColor(c);v.setTypeface(Typeface.DEFAULT,b?Typeface.BOLD:Typeface.NORMAL);return v;}
    private Button button(String x,int z){Button b=new Button(this);b.setText(x);b.setAllCaps(false);b.setTextSize(z);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return b;}private Button primary(String x){Button b=button(x,16);b.setTextColor(Color.WHITE);b.setBackground(round(purple(),16));b.setMinHeight(dp(53));return b;}private Button secondary(String x){Button b=button(x,16);b.setTextColor(Color.rgb(62,52,111));b.setBackground(stroke(Color.rgb(246,244,255),Color.rgb(215,207,244),16));b.setMinHeight(dp(53));return b;}private Button dark(String x){Button b=button(x,14);b.setTextColor(Color.WHITE);b.setBackground(stroke(Color.rgb(57,60,76),Color.rgb(84,88,108),14));b.setMinHeight(dp(50));return b;}private Button smallDark(String x){Button b=dark(x);b.setTextSize(11);b.setMinHeight(0);b.setPadding(dp(9),dp(3),dp(9),dp(3));return b;}private Button link(String x){Button b=button(x,13);b.setTextColor(purple());b.setBackgroundColor(Color.TRANSPARENT);return b;}private Button tool(String x){Button b=button(x,22);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(0,0,0,0);b.setTextColor(Color.rgb(43,45,55));b.setBackgroundColor(Color.TRANSPARENT);return b;}
    private GradientDrawable round(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}private GradientDrawable stroke(int c,int s,int r){GradientDrawable d=round(c,r);d.setStroke(dp(1),s);return d;}
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-2,-2);}private FrameLayout.LayoutParams frameMatch(){return new FrameLayout.LayoutParams(-1,-1);}private View gap(int n){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(n)));return v;}private View hgap(int n){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(dp(n),1));return v;}
    private void detach(View v){if(v!=null&&v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}private void toast(String x){Toast.makeText(this,x,Toast.LENGTH_LONG).show();}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}private int purple(){return Color.rgb(91,69,224);}private static String s(String x){return x==null?"":x;}private static String cut(String x,int n){if(x==null)return "";return x.length()<=n?x:x.substring(0,n)+"\n[…შემოკლებულია…]";}
}
