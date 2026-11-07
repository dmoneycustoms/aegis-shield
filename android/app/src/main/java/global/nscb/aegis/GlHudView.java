package global.nscb.aegis;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Full tactical 3D-style OS backdrop — perspective floor, depth rings, particles, threat bloom. */
public final class GlHudView extends GLSurfaceView {
    private final RendererImpl r;

    public GlHudView(Context c) { this(c, null); }

    public GlHudView(Context c, AttributeSet a) {
        super(c, a);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        r = new RendererImpl();
        setRenderer(r);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setThreat(float threat01) {
        r.threat = clamp(threat01);
        if (r.threat > 0.35f) setActive(true);
    }
    public void setScanning(boolean on) {
        r.scanning = on ? 1f : 0.4f;
        setActive(on || r.threat > 0.35f);
        if (on) requestRender();
    }
    public void setMode(float mode) { r.mode = clamp(mode); }

    /** continuous GL only when scanning or elevated threat — saves battery */
    public void setActive(boolean active) {
        r.wantActive = active;
        setRenderMode(active ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
        if (active) requestRender();
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static final class RendererImpl implements Renderer {
        private static final String VSH =
                "attribute vec2 aPos; varying vec2 vUv;\n" +
                "void main(){ vUv=aPos*0.5+0.5; gl_Position=vec4(aPos,0.0,1.0); }\n";
        private static final String FSH =
                "precision mediump float;\n" +
                "varying vec2 vUv; uniform vec2 uRes; uniform float uTime;\n" +
                "uniform float uThreat; uniform float uScan; uniform float uMode;\n" +
                "mat2 rot(float a){float c=cos(a),s=sin(a);return mat2(c,-s,s,c);}\n" +
                "void main(){\n" +
                "  vec2 uv=(gl_FragCoord.xy-0.5*uRes)/uRes.y;\n" +
                "  float t=uTime;\n" +
                "  vec3 amber=vec3(0.95,0.62,0.08);\n" +
                "  vec3 cyan=vec3(0.1,0.95,0.9);\n" +
                "  vec3 red=vec3(1.0,0.18,0.28);\n" +
                "  vec3 accent=mix(mix(amber,cyan,0.28),red,smoothstep(0.2,0.9,uThreat));\n" +
                "  vec3 col=vec3(0.006,0.008,0.012);\n" +
                "  float horizon=0.16+0.03*sin(t*0.35);\n" +
                "  if(uv.y<horizon){\n" +
                "    float z=(horizon-uv.y)+0.02;\n" +
                "    vec2 g=vec2(uv.x/z,1.0/z);\n" +
                "    g.x+=sin(t*0.25)*0.12; g.y+=t*0.4*(0.35+uScan);\n" +
                "    float line=smoothstep(0.04,0.0,abs(fract(g.x*2.4)-0.5))\n" +
                "              +smoothstep(0.04,0.0,abs(fract(g.y*2.4)-0.5));\n" +
                "    float fog=exp(-z*2.6);\n" +
                "    col+=accent*line*fog*0.6; col+=accent*fog*0.1;\n" +
                "  } else {\n" +
                "    col+=accent*exp(-(uv.y-horizon)*3.2)*0.07;\n" +
                "  }\n" +
                "  vec2 c=uv-vec2(0.0,0.1); c.y*=1.2;\n" +
                "  for(float i=1.0;i<=7.0;i+=1.0){\n" +
                "    vec2 p=c*rot(t*(0.2+i*0.04)+i); p.y*=0.52+0.06*sin(t+i);\n" +
                "    float rad=0.09*i+0.02*sin(t*1.2+i);\n" +
                "    float ring=exp(-abs(length(p)-rad)*75.0);\n" +
                "    col+=accent*ring*(0.1+0.2*uScan)*(1.0-i/8.0);\n" +
                "  }\n" +
                "  float a=atan(c.y*1.5,c.x);\n" +
                "  float beam=fract(a/6.28318+t*0.13);\n" +
                "  float wedge=smoothstep(0.0,0.03,beam)*(1.0-smoothstep(0.03,0.25,beam));\n" +
                "  float dist=length(vec2(c.x,c.y*1.5));\n" +
                "  col+=accent*wedge*exp(-dist*1.2)*0.7*uScan;\n" +
                "  for(float i=0.0;i<10.0;i+=1.0){\n" +
                "    float seed=i*19.1;\n" +
                "    vec2 pos=vec2(fract(sin(seed)*43758.5)*2.0-1.0,fract(sin(seed*2.3)*22421.2)*1.5-0.5);\n" +
                "    pos+=vec2(sin(t*0.5+seed),cos(t*0.4+seed))*0.06;\n" +
                "    float z=0.25+fract(sin(seed*3.1)*999.0)*0.75;\n" +
                "    vec2 q=(uv-pos)/z;\n" +
                "    float hx=max(abs(q.x)*0.866+abs(q.y)*0.5,abs(q.y));\n" +
                "    col+=accent*(1.0-smoothstep(0.015,0.04,hx))*(0.1/z)*uScan;\n" +
                "  }\n" +
                "  float core=exp(-dist*dist*(5.0+uThreat*5.0));\n" +
                "  col+=accent*core*(0.22+0.55*uThreat+0.2*uScan);\n" +
                "  col+=red*uThreat*core*0.4;\n" +
                "  vec2 e=abs(uv)-vec2(0.74,0.9);\n" +
                "  col+=accent*exp(-length(max(e,0.0))*35.0)*0.22;\n" +
                "  col+=accent*sin(gl_FragCoord.y*2.0+t*8.0)*0.012*uScan;\n" +
                "  float vig=smoothstep(1.4,0.15,length(uv*vec2(0.9,1.0)));\n" +
                "  col*=vig; col+=cyan*uMode*0.1;\n" +
                "  gl_FragColor=vec4(col,1.0);\n" +
                "}\n";

        volatile float threat, scanning = 0.45f, mode;
        volatile boolean wantActive = true;
        int prog, aPos, uRes, uTime, uThreat, uScan, uMode;
        FloatBuffer quad;
        int w = 1, h = 1;
        long t0;

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0.01f, 0.01f, 0.015f, 1f);
            int vs = sh(GLES20.GL_VERTEX_SHADER, VSH);
            int fs = sh(GLES20.GL_FRAGMENT_SHADER, FSH);
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            uRes = GLES20.glGetUniformLocation(prog, "uRes");
            uTime = GLES20.glGetUniformLocation(prog, "uTime");
            uThreat = GLES20.glGetUniformLocation(prog, "uThreat");
            uScan = GLES20.glGetUniformLocation(prog, "uScan");
            uMode = GLES20.glGetUniformLocation(prog, "uMode");
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            t0 = System.nanoTime();
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            w = Math.max(1, width); h = Math.max(1, height);
            GLES20.glViewport(0, 0, w, h);
        }

        @Override public void onDrawFrame(GL10 gl) {
            float t = (System.nanoTime() - t0) / 1e9f;
            mode *= 0.97f;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(prog);
            GLES20.glUniform2f(uRes, w, h);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uThreat, threat);
            GLES20.glUniform1f(uScan, scanning);
            GLES20.glUniform1f(uMode, mode);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
        }

        static int sh(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
