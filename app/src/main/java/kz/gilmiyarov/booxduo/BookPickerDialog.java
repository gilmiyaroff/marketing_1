package kz.gilmiyarov.booxduo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searchable BOOX book picker with lazy thumbnail previews and E-Ink-friendly rows. */
final class BookPickerDialog {
    interface Listener {
        void select(BooxLibrary.Book book);
        void unlink();
        void browseFile();
    }
    private static final LruCache<String,Bitmap> covers=new LruCache<String,Bitmap>(8*1024*1024){
        @Override protected int sizeOf(String k,Bitmap v){return v.getByteCount();}
    };
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Listener listener;
    private final ArrayList<BooxLibrary.Book> all=new ArrayList<>();
    private final ArrayList<BooxLibrary.Book> visible=new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private AlertDialog dialog;
    private TextView count;

    private BookPickerDialog(Activity context,ArrayList<BooxLibrary.Book> input,Listener listener){
        this.activity=context;
        this.listener=listener;
        LinkedHashMap<String,BooxLibrary.Book> unique=new LinkedHashMap<>();
        for(BooxLibrary.Book book:input){
            String key=book.path!=null&&!book.path.isEmpty()
                    ? "path:"+book.path.toLowerCase(Locale.ROOT)
                    : book.key();
            if(!unique.containsKey(key))unique.put(key,book);
        }
        all.addAll(unique.values());
        visible.addAll(all);
    }
    static void show(Activity context,ArrayList<BooxLibrary.Book> books,Listener callback){
        new BookPickerDialog(context,books,callback).show();
    }
    private int dp(float size){return Math.round(size*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String value,int sp,boolean bold){
        TextView v=new TextView(activity);
        v.setText(value);v.setTextSize(sp);v.setTextColor(0xff212121);
        if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);
        return v;
    }
    private void show(){
        LinearLayout body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12),0,dp(12),dp(5));

        EditText search=new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("Поиск по названию книги");
        search.setPadding(dp(8),0,dp(8),0);
        body.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));

        count=label(all.size()+" книг · выбери нужную",11,false);
        count.setTextColor(0xff777777);
        LinearLayout.LayoutParams countP=new LinearLayout.LayoutParams(-1,dp(28));
        countP.topMargin=dp(5);
        body.addView(count,countP);

        list=new ListView(activity);
        list.setDividerHeight(1);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setFastScrollEnabled(true);
        adapter=new BaseAdapter(){
            @Override public int getCount(){return visible.size();}
            @Override public Object getItem(int position){return visible.get(position);}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                final BooxLibrary.Book book=visible.get(position);
                LinearLayout row=new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(3),dp(5),dp(3),dp(5));

                ImageView cover=new ImageView(activity);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackgroundColor(0xffeeeeee);
                cover.setImageResource(android.R.drawable.ic_menu_agenda);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(46),dp(62));
                cp.rightMargin=dp(10);
                row.addView(cover,cp);

                LinearLayout info=new LinearLayout(activity);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView title=label(book.name==null||book.name.isEmpty()
                        ? fileLabel(book.path):book.name,13,true);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(title,new LinearLayout.LayoutParams(-1,-2));

                StringBuilder meta=new StringBuilder();
                if(book.hasUsableProgress()) {
                    meta.append(book.normalizedProgress()?book.percent()+"%"
                        : book.currentPage+"/"+book.totalPages+" · "+book.percent()+"%");
                }
                if(book.lastAccess>0) {
                    if(meta.length()>0)meta.append(" · ");
                    meta.append(new SimpleDateFormat("dd.MM.yy",Locale.getDefault())
                            .format(new Date(book.lastAccess)));
                }
                if(meta.length()==0)meta.append("Из библиотеки BOOX");
                TextView sub=label(meta.toString(),11,false);
                sub.setTextColor(0xff6b6b6b);
                LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);
                mp.topMargin=dp(6);
                info.addView(sub,mp);
                row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                loadCover(book,cover);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent,view,index,id)->{
            if(index<0||index>=visible.size())return;
            BooxLibrary.Book selected=visible.get(index);
            dialog.dismiss();
            listener.select(selected);
        });
        body.addView(list,new LinearLayout.LayoutParams(-1,dp(406)));

        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int a,int c,int d){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int length){
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s){}
        });
        dialog=new AlertDialog.Builder(activity)
            .setTitle("Выбери книгу")
            .setView(body)
            .setPositiveButton("Из файлов",(d,w)->listener.browseFile())
            .setNeutralButton("Отвязать",(d,w)->listener.unlink())
            .setNegativeButton("Отмена",null)
            .create();
        dialog.show();
    }
    private String fileLabel(String path){
        if(path==null||path.trim().isEmpty())return "Без названия";
        return new File(path).getName();
    }
    private void filter(String input){
        String term=input.toLowerCase(Locale.ROOT).trim();
        visible.clear();
        for(BooxLibrary.Book book:all){
            String str=(book.name+" "+book.path).toLowerCase(Locale.ROOT);
            if(str.contains(term))visible.add(book);
        }
        count.setText(visible.size()+" из "+all.size()+" книг");
        adapter.notifyDataSetChanged();
    }
    private void loadCover(BooxLibrary.Book book,ImageView target){
        if(book.path==null||book.path.trim().isEmpty())return;
        String key=book.path;
        target.setTag(key);
        Bitmap current=covers.get(key);
        if(current!=null){target.setImageBitmap(current);return;}
        worker.execute(()->{
            Bitmap bmp=null;
            try{
                File file=new File(key);
                if(file.isFile()&&file.canRead()){
                    Uri uri=SharedFileProvider.uriFor(file);
                    bmp=BookPreview.extract(activity.getApplicationContext(),uri,file.getName());
                }
            }catch(Exception ignored){}
            if(bmp==null)return;
            Bitmap small=Bitmap.createScaledBitmap(bmp,100,135,true);
            if(small!=bmp)bmp.recycle();
            Bitmap result=small;
            activity.runOnUiThread(()->{
                covers.put(key,result);
                if(dialog!=null&&dialog.isShowing()
                        &&key.equals(target.getTag())){
                    target.setImageBitmap(result);
                }
            });
        });
    }
}
