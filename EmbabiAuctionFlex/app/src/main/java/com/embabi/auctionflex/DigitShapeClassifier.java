package com.embabi.auctionflex;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DigitShapeClassifier {
    private DigitShapeClassifier() {}

    public static final class RatingResult {
        public final int rating;
        public final float confidence;
        RatingResult(int rating,float confidence) { this.rating=rating; this.confidence=confidence; }
    }

    private static final int W=32,H=48,CELL=8,BINS=8,FEAT=192,CLASSES=10;
    private static final String MODEL="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";
    private static final float[][] WEIGHTS=new float[CLASSES][FEAT];
    private static final float[] BIAS=new float[CLASSES];

    static {
        byte[] raw=Base64.decode(MODEL,Base64.DEFAULT);
        ByteBuffer bb=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for(int d=0;d<CLASSES;d++) for(int i=0;i<FEAT;i++) WEIGHTS[d][i]=bb.getFloat();
        for(int d=0;d<CLASSES;d++) BIAS[d]=bb.getFloat();
    }

    public static RatingResult predictRating(Bitmap rating) {
        if(rating==null || rating.getWidth()<12 || rating.getHeight()<18) return null;
        float[] centers={0.48f,0.50f,0.52f};
        RatingResult best=null;

        for(float center:centers) {
            int w=rating.getWidth(),h=rating.getHeight();
            int leftR=Math.max(3,Math.min(w-3,Math.round((center+0.055f)*w)));
            int rightL=Math.max(2,Math.min(w-4,Math.round((center-0.055f)*w)));
            Bitmap a=null,b=null;
            try {
                a=Bitmap.createBitmap(rating,0,0,leftR,h);
                b=Bitmap.createBitmap(rating,rightL,0,w-rightL,h);
                DigitResult d1=predictDigit(a,true);
                DigitResult d2=predictDigit(b,false);
                if(d1==null||d2==null) continue;
                int value=d1.digit*10+d2.digit;
                float conf=Math.min(d1.margin,d2.margin);
                if(value>=70&&value<=99&&(best==null||conf>best.confidence))
                    best=new RatingResult(value,conf);
            } catch(Exception ignored) {
            } finally {
                if(a!=null) a.recycle();
                if(b!=null) b.recycle();
            }
        }
        return best!=null&&best.confidence>=0.10f?best:null;
    }

    private static final class DigitResult {
        final int digit; final float margin;
        DigitResult(int digit,float margin){this.digit=digit;this.margin=margin;}
    }

    private static DigitResult predictDigit(Bitmap src,boolean first) {
        float[] f=features(src);
        if(f==null) return null;
        int best=-1;
        float bs=-Float.MAX_VALUE,ss=-Float.MAX_VALUE;

        for(int d=0;d<10;d++) {
            if(first&&d<7) continue;
            float s=BIAS[d];
            for(int i=0;i<FEAT;i++) s+=WEIGHTS[d][i]*f[i];
            if(s>bs){ss=bs;bs=s;best=d;}
            else if(s>ss) ss=s;
        }
        return best<0?null:new DigitResult(best,bs-ss);
    }

    private static float[] features(Bitmap src) {
        Bitmap scaled=null;
        try {
            scaled=Bitmap.createScaledBitmap(src,W,H,true);
            int[] px=new int[W*H];
            scaled.getPixels(px,0,W,0,0,W,H);
            float[] gray=new float[W*H];
            int[] hist=new int[256];

            for(int i=0;i<px.length;i++) {
                int c=px[i];
                int y=(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000;
                gray[i]=y; hist[y]++;
            }

            int p5=percentile(hist,px.length,0.05f);
            int p95=percentile(hist,px.length,0.95f);
            float den=Math.max(1f,p95-p5);
            for(int i=0;i<gray.length;i++)
                gray[i]=Math.max(0f,Math.min(255f,(gray[i]-p5)*255f/den));

            float[] gx=new float[W*H],gy=new float[W*H];
            for(int y=1;y<H-1;y++) for(int x=1;x<W-1;x++) {
                int i=y*W+x;
                gx[i]=gray[i+1]-gray[i-1];
                gy[i]=gray[i+W]-gray[i-W];
            }

            float[] out=new float[FEAT];
            int k=0;
            for(int cy=0;cy<H;cy+=CELL) for(int cx=0;cx<W;cx+=CELL) {
                float[] hh=new float[BINS];
                for(int y=cy;y<cy+CELL;y++) for(int x=cx;x<cx+CELL;x++) {
                    int i=y*W+x;
                    float dx=gx[i],dy=gy[i];
                    float mag=(float)Math.sqrt(dx*dx+dy*dy);
                    float ang=(float)Math.toDegrees(Math.atan2(dy,dx));
                    while(ang<0) ang+=180f;
                    while(ang>=180f) ang-=180f;
                    int bin=(int)(ang/180f*BINS);
                    if(bin<0)bin=0; if(bin>=BINS)bin=BINS-1;
                    hh[bin]+=mag;
                }
                float n=1e-6f;
                for(float v:hh)n+=v*v;
                n=(float)Math.sqrt(n);
                for(int b=0;b<BINS;b++)out[k++]=hh[b]/n;
            }

            float n=1e-6f;
            for(float v:out)n+=v*v;
            n=(float)Math.sqrt(n);
            for(int i=0;i<out.length;i++)out[i]/=n;
            return out;
        } catch(Exception e) {
            return null;
        } finally {
            if(scaled!=null&&scaled!=src)scaled.recycle();
        }
    }

    private static int percentile(int[] hist,int total,float q) {
        int target=Math.max(0,Math.min(total-1,Math.round((total-1)*q))),acc=0;
        for(int i=0;i<hist.length;i++){acc+=hist[i];if(acc>target)return i;}
        return 255;
    }
}
