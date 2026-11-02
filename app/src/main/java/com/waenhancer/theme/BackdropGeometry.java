package com.waenhancer.theme;

/** Pure affine coverage gate for native wallpaper projections, including 90-degree rotation/RTL. */
public final class BackdropGeometry {
    private BackdropGeometry() { }
    public static boolean covers(float[] m, int sourceWidth, int sourceHeight, int width, int height) {
        if (m==null || m.length<9 || sourceWidth<=0 || sourceHeight<=0 || width<=0 || height<=0) return false;
        for (float f:m) if (!Float.isFinite(f)) return false;
        if (m[6]!=0 || m[7]!=0 || m[8]!=1) return false;
        float sx=(float)Math.hypot(m[0],m[3]), sy=(float)Math.hypot(m[1],m[4]);
        if (sx<=0 || Math.abs(sx-sy)>Math.max(sx,sy)*.001f) return false;
        if (!((m[1]==0 && m[3]==0) || (m[0]==0 && m[4]==0))) return false;
        float x1=m[2], x2=m[0]*sourceWidth+m[2], x3=m[1]*sourceHeight+m[2], x4=x2+x3-m[2];
        float y1=m[5], y2=m[3]*sourceWidth+m[5], y3=m[4]*sourceHeight+m[5], y4=y2+y3-m[5];
        return Math.min(Math.min(x1,x2),Math.min(x3,x4))<=.5f
                && Math.min(Math.min(y1,y2),Math.min(y3,y4))<=.5f
                && Math.max(Math.max(x1,x2),Math.max(x3,x4))>=width-.5f
                && Math.max(Math.max(y1,y2),Math.max(y3,y4))>=height-.5f;
    }
}
