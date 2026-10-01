package com.embabi.auctionflex;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class DigitRatingModel {
    public static final class Result {
        public final Integer rating;
        public final float confidence;
        public final int tens;
        public final int units;
        Result(Integer rating,float confidence,int tens,int units) {
            this.rating=rating; this.confidence=confidence; this.tens=tens; this.units=units;
        }
    }

    private static final int IN=640;
    private static final int HIDDEN=64;
    private static final int OUT=10;

    private final byte[] w1;
    private final float[] b1;
    private final byte[] w2;
    private final float[] b2;
    private final float s1,s2;
    private final boolean ready;

    public DigitRatingModel(Context c) {
        byte[] tw1=null, tw2=null;
        float[] tb1=null, tb2=null;
        float ts1=0f, ts2=0f;
        boolean ok=false;
        try {
            StringBuilder enc=new StringBuilder(56000);
            for(int i=1;i<=8;i++) enc.append(readAsset(c,"digit_model_"+i+".b64"));
            byte[] raw=Base64.decode(enc.toString(),Base64.DEFAULT);
            ByteBuffer bb=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic=new byte[4]; bb.get(magic);
            if(magic[0]!='E'||magic[1]!='R'||magic[2]!='D'||magic[3]!='1') throw new IllegalStateException("bad model");
            int in=bb.getInt(), hidden=bb.getInt(), out=bb.getInt();
            if(in!=IN||hidden!=HIDDEN||out!=OUT) throw new IllegalStateException("shape");
            ts1=bb.getFloat(); ts2=bb.getFloat();
            tw1=new byte[HIDDEN*IN]; bb.get(tw1);
            tb1=new float[HIDDEN]; for(int i=0;i<HIDDEN;i++) tb1[i]=bb.getFloat();
            tw2=new byte[OUT*HIDDEN]; bb.get(tw2);
            tb2=new float[OUT]; for(int i=0;i<OUT;i++) tb2[i]=bb.getFloat();
            ok=true;
        } catch(Exception ignored) {}
        w1=tw1; b1=tb1; w2=tw2; b2=tb2; s1=ts1; s2=ts2; ready=ok;
    }

    private String readAsset(Context c,String name) throws Exception {
        InputStream in=c.getAssets().open(name);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[4096];
        int n;
        while((n=in.read(buf))>0) out.write(buf,0,n);
        in.close();
        return out.toString("US-ASCII").trim();
    }

    public boolean isReady(){ return ready; }

    public Result predict(Bitmap cardBox) {
        if(!ready || cardBox==null || cardBox.getWidth()<16 || cardBox.getHeight()<24)
            return new Result(null,0f,-1,-1);

        int w=cardBox.getWidth(), h=cardBox.getHeight();
        int ratingH=Math.max(8,Math.min(h,Math.round(h*.56f)));
        int mid=w/2;
        int overlap=Math.max(1,Math.round(w*.045f));

        Bitmap left=null,right=null;
        try {
            left=Bitmap.createBitmap(cardBox,0,0,Math.min(w,mid+overlap),ratingH);
            int rx=Math.max(0,mid-overlap);
            right=Bitmap.createBitmap(cardBox,rx,0,w-rx,ratingH);

            Digit a=predictDigit(left);
            Digit b=predictDigit(right);

            if(a.digit<0 || b.digit<0) return new Result(null,Math.min(a.conf,b.conf),a.digit,b.digit);
            if(a.digit!=8 && a.digit!=9) return new Result(null,Math.min(a.conf,b.conf),a.digit,b.digit);

            float conf=Math.min(a.conf,b.conf);
            if(a.conf<.60f || b.conf<.60f) return new Result(null,conf,a.digit,b.digit);

            int value=a.digit*10+b.digit;
            if(value<80 || value>99) return new Result(null,conf,a.digit,b.digit);
            return new Result(value,conf,a.digit,b.digit);
        } catch(Exception e) {
            return new Result(null,0f,-1,-1);
        } finally {
            if(left!=null && !left.isRecycled()) left.recycle();
            if(right!=null && !right.isRecycled()) right.recycle();
        }
    }

    private static final class Digit {
        final int digit; final float conf;
        Digit(int d,float c){digit=d;conf=c;}
    }

    private Digit predictDigit(Bitmap src) {
        float[] x=feature(src);
        float[] hidden=new float[HIDDEN];

        for(int j=0;j<HIDDEN;j++) {
            float sum=b1[j];
            int off=j*IN;
            float acc=0f;
            for(int i=0;i<IN;i++) acc += x[i]*w1[off+i];
            sum += acc*s1;
            hidden[j]=Math.max(0f,sum);
        }

        float[] logits=new float[OUT];
        int best=0;
        for(int k=0;k<OUT;k++) {
            float sum=b2[k];
            int off=k*HIDDEN;
            float acc=0f;
            for(int j=0;j<HIDDEN;j++) acc += hidden[j]*w2[off+j];
            logits[k]=sum+acc*s2;
            if(logits[k]>logits[best]) best=k;
        }

        float max=logits[best], den=0f;
        for(int k=0;k<OUT;k++) den += (float)Math.exp(logits[k]-max);
        float conf=den>0f ? 1f/den : 0f;
        return new Digit(best,conf);
    }

    private float[] feature(Bitmap src) {
        final int W=20,H=32;
        Bitmap sm=Bitmap.createScaledBitmap(src,W,H,true);
        int[] px=new int[W*H];
        sm.getPixels(px,0,W,0,0,W,H);
        sm.recycle();

        float[] g=new float[W*H];
        for(int i=0;i<px.length;i++) {
            int c=px[i];
            g[i]=(Color.red(c)*299f+Color.green(c)*587f+Color.blue(c)*114f)/1000f;
        }

        float[] mag=new float[W*H];
        for(int y=1;y<H-1;y++) for(int x=1;x<W-1;x++) {
            int p=y*W+x;
            float gx=
                    -g[p-W-1] + g[p-W+1]
                    -2f*g[p-1] + 2f*g[p+1]
                    -g[p+W-1] + g[p+W+1];
            float gy=
                    -g[p-W-1] -2f*g[p-W] -g[p-W+1]
                    +g[p+W-1] +2f*g[p+W] +g[p+W+1];
            mag[p]=(float)Math.sqrt(gx*gx+gy*gy);
        }

        float[] sorted=mag.clone();
        Arrays.sort(sorted);
        float p95=sorted[Math.min(sorted.length-1,Math.round((sorted.length-1)*.95f))];
        if(p95<1f) p95=1f;

        float[] out=new float[W*H];
        for(int i=0;i<out.length;i++) out[i]=Math.min(1f,mag[i]/p95);
        return out;
    }
}
