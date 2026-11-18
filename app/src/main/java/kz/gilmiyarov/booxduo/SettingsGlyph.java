package kz.gilmiyarov.booxduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Monochrome, stroke-only icons for E-Ink. No emoji fonts or external assets. */
public final class SettingsGlyph extends View {
    private final String kind;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    public SettingsGlyph(Context ctx, String kind) {
        super(ctx);
        this.kind = kind;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        pen.setStyle(Paint.Style.STROKE);
        pen.setColor(0xff2c2c2c);
        pen.setStrokeWidth(2.4f);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
    }
    private void line(Canvas c, float x1,float y1,float x2,float y2) { c.drawLine(x1,y1,x2,y2,pen); }
    private void circle(Canvas c,float x,float y,float r) { c.drawCircle(x,y,r,pen); }
    private void rect(Canvas c,float l,float t,float r,float b,float radius) { c.drawRoundRect(new RectF(l,t,r,b),radius,radius,pen); }
    private void arc(Canvas c,float l,float t,float r,float b,float start,float sweep) {
        c.drawArc(new RectF(l,t,r,b),start,sweep,false,pen);
    }
    private void path(Canvas c,float... xy) {
        if(xy.length<4)return;
        Path p=new Path();p.moveTo(xy[0],xy[1]);
        for(int i=2;i+1<xy.length;i+=2)p.lineTo(xy[i],xy[i+1]);
        c.drawPath(p,pen);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int save=c.save();
        float scale=Math.min(getWidth(),getHeight())/48f;
        c.translate((getWidth()-48*scale)/2f,(getHeight()-48*scale)/2f);
        c.scale(scale,scale);
        switch(kind) {
            case "wifi":
                arc(c,4,7,44,46,220,100);arc(c,10,14,38,44,220,100);
                arc(c,17,23,31,40,220,100);circle(c,24,37,2);break;
            case "bluetooth":
                path(c,24,5,35,15,16,34);path(c,24,43,35,33,16,14);
                line(c,24,5,24,43);line(c,24,5,35,15);line(c,24,43,35,33);break;
            case "storage":
                rect(c,7,9,41,39,4);line(c,8,29,40,29);
                circle(c,33,34,1.5f);circle(c,27,34,1.5f);break;
            case "apps":
                rect(c,7,7,19,19,2);rect(c,29,7,41,19,2);
                rect(c,7,29,19,41,2);rect(c,29,29,41,41,2);break;
            case "screen":
                rect(c,6,8,42,34,3);line(c,24,34,24,41);line(c,15,41,33,41);break;
            case "battery":
                rect(c,12,9,35,40,3);line(c,20,5,27,5);
                path(c,27,15,20,26,27,26,20,36);break;
            case "clock":
                circle(c,24,24,18);line(c,24,24,24,12);line(c,24,24,33,28);break;
            case "globe":
                circle(c,24,24,18);arc(c,16,6,32,42,90,180);
                arc(c,16,6,32,42,270,180);line(c,6,24,42,24);
                arc(c,6,11,42,37,20,140);arc(c,6,11,42,37,200,140);break;
            case "update":
                arc(c,8,8,40,40,215,260);path(c,37,6,40,19,28,17);
                arc(c,8,8,40,40,35,125);path(c,10,41,8,29,21,31);break;
            case "settings":
                circle(c,24,24,15);circle(c,24,24,6);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;
                    line(c,24+(float)Math.cos(a)*16,24+(float)Math.sin(a)*16,
                           24+(float)Math.cos(a)*21,24+(float)Math.sin(a)*21);}break;
            case "lock":
                rect(c,12,21,36,41,4);arc(c,16,5,32,30,180,180);
                circle(c,24,30,1.8f);break;
            case "account":
                circle(c,24,15,8);arc(c,7,24,41,49,190,160);break;
            case "keyboard":
                rect(c,5,12,43,36,3);
                for(int y=19;y<=27;y+=7)for(int x=12;x<=36;x+=8)line(c,x,y,x+3,y);
                line(c,17,33,31,33);break;
            case "notifications":
                arc(c,12,10,36,38,195,150);
                path(c,12,31,8,36,40,36,36,31);arc(c,19,34,29,44,0,180);break;
            case "about":
                circle(c,24,24,18);circle(c,24,13,1.6f);
                line(c,24,21,24,34);break;
            case "book":
                path(c,24,13,12,9,7,10,7,36,14,34,24,38,34,34,41,36,41,10,36,9,24,13);
                line(c,24,13,24,38);break;
            case "folder":
                path(c,5,16,19,16,23,11,43,11,43,38,5,38,5,16);break;
            case "key":
                circle(c,17,17,9);line(c,24,24,40,40);line(c,35,35,41,29);
                line(c,31,31,37,25);break;
            case "access":
                circle(c,24,8,4);line(c,9,17,39,17);line(c,24,18,24,29);
                line(c,24,29,15,42);line(c,24,29,33,42);
                line(c,18,18,12,30);line(c,30,18,36,30);break;
            case "power":
                arc(c,8,9,40,41,145,250);line(c,24,3,24,24);break;
            case "files":
                path(c,12,5,29,5,39,15,39,43,12,43,12,5);
                line(c,29,5,29,15);line(c,29,15,39,15);
                line(c,17,25,34,25);line(c,17,32,34,32);break;
            case "home":
                path(c,4,23,24,6,44,23);path(c,10,19,10,42,38,42,38,19);
                rect(c,20,29,29,42,1);break;
            case "shield":
                path(c,24,4,40,11,37,30,24,43,11,30,8,11,24,4);
                path(c,17,24,22,29,32,17);break;
            case "buttons":
                rect(c,12,5,36,43,3);rect(c,19,30,29,38,2);
                circle(c,24,16,4);break;
            case "cloud":
                arc(c,8,17,30,41,190,150);arc(c,23,10,43,34,210,220);
                line(c,13,36,38,36);break;
            default:rect(c,9,9,39,39,5);
        }
        c.restoreToCount(save);
    }
}
