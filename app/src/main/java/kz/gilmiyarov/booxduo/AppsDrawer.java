package kz.gilmiyarov.booxduo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Home-screen-like app drawer: standalone icons, persistent order, and
 * drag one app onto another to create folders; drag onto a folder to add.
 * All layout and folders are local to BOOX Duo; installed apps are unchanged.
 */
final class AppsDrawer extends LinearLayout {
    private static final int INK = 0xff202020;
    private static final int MUTED = 0xff686868;
    private static final int EDGE = 0xffcecece;
    private static final int WASH = 0xfff6f6f6;
    private final Activity host;
    private final PackageManager pm;
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, App> installed = new LinkedHashMap<>();
    private final ArrayList<Node> nodes = new ArrayList<>();
    private LinearLayout grid;
    private EditText search;

    private static class App {
        String id, label;
        ComponentName component;
        App(String label, ComponentName component) {
            this.component=component;
            this.id=component.flattenToString();
            this.label=label;
        }
    }
    private static class Node {
        String id, name;
        boolean folder;
        ArrayList<String> children=new ArrayList<>();
        static Node app(String component) {
            Node n=new Node();n.id=component;return n;
        }
        static Node folder(String name) {
            Node n=new Node();n.folder=true;
            n.name=name;n.id="folder_"+System.nanoTime();
            return n;
        }
    }

    AppsDrawer(Activity host) {
        super(host);
        this.host=host;
        this.pm=host.getPackageManager();
        this.prefs=host.getSharedPreferences("duo",Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setPadding(dp(5),dp(4),dp(5),dp(15));
        scan();
        load();
        build();
    }
    private int dp(float x) {
        return Math.round(x*getResources().getDisplayMetrics().density);
    }
    private TextView text(String s,int sp,boolean strong) {
        TextView v=new TextView(host);
        v.setText(s);v.setTextSize(sp);v.setTextColor(INK);
        if(strong)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private GradientDrawable panel(int color) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(8));d.setStroke(dp(1),EDGE);
        return d;
    }
    private void scan() {
        installed.clear();
        Intent intent=new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> results=pm.queryIntentActivities(intent,0);
        for(ResolveInfo info:results) {
            if(info.activityInfo==null)continue;
            if(host.getPackageName().equals(info.activityInfo.packageName))continue;
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            CharSequence label=info.loadLabel(pm);
            App app=new App(label==null?component.getPackageName():label.toString(),component);
            installed.put(app.id,app);
        }
    }
    private void load() {
        nodes.clear();
        Set<String> accounted=new HashSet<>();
        String raw=prefs.getString("app_grid_v1",null);
        if(raw!=null)try {
            JSONArray saved=new JSONArray(raw);
            for(int i=0;i<saved.length();i++) {
                JSONObject item=saved.optJSONObject(i);
                if(item==null)continue;
                Node n=new Node();
                n.folder=item.optBoolean("folder",false);
                n.id=item.optString("id","");
                n.name=item.optString("name","Папка");
                if(n.folder) {
                    JSONArray members=item.optJSONArray("children");
                    if(members!=null)for(int j=0;j<members.length();j++) {
                        String key=members.optString(j);
                        if(installed.containsKey(key)&&accounted.add(key))
                            n.children.add(key);
                    }
                    if(n.children.size()>=2)nodes.add(n);
                    else if(n.children.size()==1)nodes.add(Node.app(n.children.get(0)));
                } else if(installed.containsKey(n.id)&&accounted.add(n.id)) {
                    nodes.add(n);
                }
            }
        }catch(Exception ignored){}
        ArrayList<App> extra=new ArrayList<>(installed.values());
        Collections.sort(extra,(a,b)->a.label.compareToIgnoreCase(b.label));
        for(App app:extra)if(accounted.add(app.id))nodes.add(Node.app(app.id));
    }
    private void save() {
        JSONArray arr=new JSONArray();
        for(Node node:nodes) {
            JSONObject json=new JSONObject();
            try {
                json.put("id",node.id).put("name",node.name).put("folder",node.folder);
                if(node.folder)json.put("children",new JSONArray(node.children));
                arr.put(json);
            } catch(Exception ignored){}
        }
        prefs.edit().putString("app_grid_v1",arr.toString()).apply();
    }
    private void build() {
        removeAllViews();
        TextView lead=text("ПРИЛОЖЕНИЯ",11,true);
        lead.setTextColor(MUTED);
        addView(lead, new LayoutParams(-1,dp(25)));

        search=new EditText(host);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Найти приложение или папку");
        search.setBackground(panel(Color.WHITE));
        search.setPadding(dp(12),dp(4),dp(12),dp(4));
        LayoutParams searchP=new LayoutParams(-1,dp(43));
        searchP.bottomMargin=dp(11);
        addView(search,searchP);

        TextView hint=text("Удерживай значок и перетащи на другой — создастся папка",11,false);
        hint.setTextColor(MUTED);
        LayoutParams hintP=new LayoutParams(-1,-2);
        hintP.bottomMargin=dp(12);
        addView(hint,hintP);

        grid=new LinearLayout(host);
        grid.setOrientation(VERTICAL);
        addView(grid,new LayoutParams(-1,-2));
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                renderGrid();
            }
            public void afterTextChanged(Editable s){}
        });
        renderGrid();
    }
    private void renderGrid() {
        if(grid==null)return;
        grid.removeAllViews();
        String filter=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<Node> visible=new ArrayList<>();
        for(Node n:nodes){
            if(filter.isEmpty() || (n.folder && n.name.toLowerCase(Locale.ROOT).contains(filter))) {
                visible.add(n);continue;
            }
            if(n.folder) {
                boolean match=false;
                for(String member:n.children){
                    App a=installed.get(member);
                    if(a!=null&&a.label.toLowerCase(Locale.ROOT).contains(filter))match=true;
                }
                if(match)visible.add(n);
            } else {
                App a=installed.get(n.id);
                if(a!=null&&(a.label+" "+a.component.getPackageName())
                        .toLowerCase(Locale.ROOT).contains(filter))visible.add(n);
            }
        }
        if(visible.isEmpty()) {
            grid.addView(text("Ничего не найдено",13,false));
            return;
        }
        final int columns=4;
        for(int offset=0;offset<visible.size();offset+=columns){
            LinearLayout row=new LinearLayout(host);
            row.setOrientation(HORIZONTAL);
            for(int col=0;col<columns;col++){
                int ix=offset+col;
                if(ix>=visible.size()){
                    row.addView(new View(host),new LayoutParams(0,dp(104),1));
                } else {
                    Node node=visible.get(ix);
                    LinearLayout tile=makeTile(node);
                    row.addView(tile,new LayoutParams(0,dp(104),1));
                }
            }
            grid.addView(row,new LayoutParams(-1,-2));
        }
    }
    private LinearLayout makeTile(Node node){
        LinearLayout tile=new LinearLayout(host);
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(2),dp(6),dp(2),dp(2));

        View icon=node.folder?folderIcon(node):appIcon(installed.get(node.id),dp(51));
        LayoutParams ip=new LayoutParams(dp(54),dp(54));
        ip.gravity=Gravity.CENTER_HORIZONTAL;
        ip.bottomMargin=dp(5);
        tile.addView(icon,ip);

        TextView title=text(node.folder?node.name:
                installed.containsKey(node.id)?installed.get(node.id).label:"Приложение",11,false);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(title,new LayoutParams(-1,-2));
        tile.setOnClickListener(v-> {
            if(node.folder)showFolder(node);
            else launch(node.id);
        });
        tile.setOnLongClickListener(v -> {
            ClipData data=ClipData.newPlainText("boox-duo-app",node.id);
            v.startDragAndDrop(data,new View.DragShadowBuilder(v),node.id,0);
            return true;
        });
        tile.setOnDragListener((v,event)->{
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_STARTED: return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    tile.setBackground(panel(WASH)); return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    tile.setBackgroundColor(Color.TRANSPARENT);return true;
                case DragEvent.ACTION_DROP:
                    tile.setBackgroundColor(Color.TRANSPARENT);
                    String source=event.getLocalState() instanceof String
                            ?(String)event.getLocalState():null;
                    if(source==null||source.equals(node.id))return true;
                    drop(source,node.id);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    tile.setBackgroundColor(Color.TRANSPARENT);return true;
            }
            return true;
        });
        return tile;
    }
    private ImageView appIcon(App app,int size){
        ImageView img=new ImageView(host);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if(app!=null)try{
            img.setImageDrawable(pm.getActivityIcon(app.component));
        }catch(Exception ex){
            try{img.setImageDrawable(pm.getApplicationIcon(app.component.getPackageName()));}
            catch(Exception ignored){img.setImageResource(android.R.drawable.sym_def_app_icon);}
        } else img.setImageResource(android.R.drawable.sym_def_app_icon);
        return img;
    }
    private View folderIcon(Node folder){
        LinearLayout backdrop=new LinearLayout(host);
        backdrop.setOrientation(VERTICAL);
        backdrop.setPadding(dp(5),dp(5),dp(5),dp(4));
        backdrop.setBackground(panel(WASH));
        for(int r=0;r<2;r++){
            LinearLayout line=new LinearLayout(host);
            for(int c=0;c<2;c++){
                int index=r*2+c;
                View mini;
                if(index<folder.children.size())mini=appIcon(installed.get(folder.children.get(index)),dp(18));
                else mini=new View(host);
                LayoutParams p=new LayoutParams(0,dp(20),1);
                if(c==0)p.rightMargin=dp(2);
                line.addView(mini,p);
            }
            backdrop.addView(line,new LayoutParams(-1,dp(20)));
        }
        return backdrop;
    }
    private Node find(String id){
        for(Node n:nodes)if(n.id.equals(id))return n;
        return null;
    }
    private void drop(String sourceId,String targetId){
        Node from=find(sourceId),to=find(targetId);
        if(from==null||to==null||from==to)return;
        if(!from.folder&&!to.folder) {
            Node folder=Node.folder("Папка");
            folder.children.add(to.id);
            folder.children.add(from.id);
            int index=nodes.indexOf(to);
            nodes.remove(from);
            nodes.remove(to);
            nodes.add(Math.min(index,nodes.size()),folder);
            Toast.makeText(host,"Папка создана",Toast.LENGTH_SHORT).show();
        } else if(!from.folder&&to.folder){
            if(!to.children.contains(from.id))to.children.add(from.id);
            nodes.remove(from);
        } else {
            int a=nodes.indexOf(from),b=nodes.indexOf(to);
            Collections.swap(nodes,a,b);
        }
        save();
        post(this::renderGrid);
    }
    private void launch(String id){
        App app=installed.get(id);
        if(app==null)return;
        try{
            Intent intent=new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(app.component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
        }catch(Exception ex){
            Toast.makeText(host,"Не удалось запустить приложение",Toast.LENGTH_LONG).show();
        }
    }
    private void details(String id){
        App a=installed.get(id);
        if(a==null)return;
        try{
            Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+a.component.getPackageName()));
            host.startActivity(i);
        }catch(Exception ignored){}
    }
    private void showFolder(Node folder){
        LinearLayout content=new LinearLayout(host);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(14),dp(8),dp(14),dp(8));
        TextView hint=text("Нажми для запуска. Удерживай — чтобы вынести из папки.",11,false);
        hint.setTextColor(MUTED);
        content.addView(hint,new LayoutParams(-1,-2));

        final int columns=3;
        for(int offset=0;offset<folder.children.size();offset+=columns){
            LinearLayout row=new LinearLayout(host);
            for(int col=0;col<columns;col++){
                int index=offset+col;
                if(index>=folder.children.size()){
                    row.addView(new View(host),new LayoutParams(0,dp(95),1));
                    continue;
                }
                String id=folder.children.get(index);
                App app=installed.get(id);
                LinearLayout tile=new LinearLayout(host);
                tile.setOrientation(VERTICAL);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView image=appIcon(app,dp(40));
                LayoutParams iconP=new LayoutParams(dp(43),dp(43));iconP.gravity=Gravity.CENTER_HORIZONTAL;
                tile.addView(image,iconP);
                TextView label=text(app==null?"Приложение":app.label,11,false);
                label.setGravity(Gravity.CENTER);label.setMaxLines(2);
                tile.addView(label,new LayoutParams(-1,-2));
                tile.setOnClickListener(v->launch(id));
                tile.setOnLongClickListener(v->{
                    new AlertDialog.Builder(host)
                        .setTitle(app==null?"Приложение":app.label)
                        .setItems(new String[]{"Вынести из папки","Сведения о приложении"},(d,choice)->{
                            if(choice==0){
                                folder.children.remove(id);
                                if(folder.children.size()<2){
                                    int position=nodes.indexOf(folder);
                                    nodes.remove(folder);
                                    if(!folder.children.isEmpty())
                                        nodes.add(position,Node.app(folder.children.get(0)));
                                }
                                nodes.add(Node.app(id));
                                save();renderGrid();showFolderIfAny(folder.id);
                            }else details(id);
                        }).show();
                    return true;
                });
                row.addView(tile,new LayoutParams(0,dp(93),1));
            }
            content.addView(row);
        }
        ScrollView scroll=new ScrollView(host);
        scroll.addView(content);
        new AlertDialog.Builder(host)
            .setTitle(folder.name)
            .setView(scroll)
            .setPositiveButton("Переименовать",(d,w)->rename(folder))
            .setNeutralButton("Сведения", (d,w)->new AlertDialog.Builder(host)
                .setTitle("Папка")
                .setMessage("Удерживай приложение на основном экране и перетащи на папку, чтобы добавить его.")
                .setPositiveButton("ОК",null).show())
            .setNegativeButton("Закрыть",null)
            .show();
    }
    private void showFolderIfAny(String id){
        Node current=find(id);
        if(current!=null&&current.folder)post(()->showFolder(current));
    }
    private void rename(Node folder){
        EditText input=new EditText(host);
        input.setSingleLine(true);input.setText(folder.name);
        new AlertDialog.Builder(host).setTitle("Название папки")
            .setView(input)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить",(d,w)->{
                String name=input.getText().toString().trim();
                if(!name.isEmpty())folder.name=name;
                save();renderGrid();
            }).show();
    }
}
