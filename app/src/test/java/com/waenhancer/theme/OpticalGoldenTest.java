package com.waenhancer.theme;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Pixel golden for the CPU geometry/transfer mirror under an IDEAL Gaussian assumption.
 * No tint, lighting, driver kernel or HWUI render-target behavior is represented here.
 */
public class OpticalGoldenTest {
    @Test public void nativePixelModelMatchesRecordedGolden() throws Exception {
        for (GlassOptics.Profile profile : GlassOptics.Profile.values()) {
            try (InputStream expected = getClass().getResourceAsStream("/optics/"+profile.key()+".pgm")) {
                assertNotNull(expected);
                assertArrayEquals(profile.key(),expected.readAllBytes(),render(profile));
            }
        }
    }

    public static byte[] render(GlassOptics.Profile profile) throws Exception {
        int w=96,h=48;
        GlassSpec spec=GlassSpec.resolve(GlassSpec.Variant.LIQUID,true,0,0,10f,true,false);
        LensModel.Surface surface=LensModel.surface(spec,w,h,14f,2f,true,true,0.28f);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write("P5\n96 48\n255\n".getBytes(StandardCharsets.US_ASCII));
        float[] at=new float[6], xp=new float[6], xm=new float[6], yp=new float[6], ym=new float[6];
        double frequency=Math.hypot(1d/8,1d/16);
        double softResponse=LensModel.gaussianResponse(LensModel.sigmaPx(spec,2f),frequency);
        double detailResponse=profile==GlassOptics.Profile.RECONSTRUCT
                ? LensModel.gaussianResponse(LensModel.detailRadiusPx(2f),frequency) : 1d;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            float px=x+0.5f,py=y+0.5f;
            LensModel.sample(surface,px,py,true,at);
            float coverage=LensModel.coverage(surface,px,py);
            float d=LensModel.sdRoundRect(px-w/2f,py-h/2f,w/2f,h/2f,surface.radius);
            float depth=Math.max(0f,Math.min(1f,-d/surface.bevel));
            float offset=(float)Math.hypot(at[2]-px,at[3]-py)/surface.cap;
            float beta=LensModel.beta(depth,profile,offset);
            double wave=wave(at[2],at[3]);
            if (profile==GlassOptics.Profile.RECONSTRUCT) {
                LensModel.sample(surface,px+0.5f,py,true,xp); LensModel.sample(surface,px-0.5f,py,true,xm);
                LensModel.sample(surface,px,py+0.5f,true,yp); LensModel.sample(surface,px,py-0.5f,true,ym);
                double ax=(xp[2]-xm[2])*0.45, ay=(xp[3]-xm[3])*0.45;
                double bx=(yp[2]-ym[2])*0.45, by=(yp[3]-ym[3])*0.45;
                wave=wave*0.5+(wave(at[2]+ax,at[3]+ay)+wave(at[2]-ax,at[3]-ay)
                        +wave(at[2]+bx,at[3]+by)+wave(at[2]-bx,at[3]-by))*0.125;
            }
            double detail=0.5+0.5*detailResponse*wave, soft=0.5+0.5*softResponse*wave;
            double encoded=profile==GlassOptics.Profile.RECONSTRUCT
                    ? LensModel.fromLinear((1-beta)*detail+beta*soft)
                    : (1-beta)*LensModel.fromLinear(detail)+beta*LensModel.fromLinear(soft);
            if (coverage<=0.004f) coverage=0f;
            out.write((int)Math.round(255*(encoded*coverage+0.5*(1-coverage))));
        }
        return out.toByteArray();
    }
    private static double wave(double x,double y) { return Math.sin(2*Math.PI*(x/8+y/16)); }
}
