package android.content.pm;
public class PackageManager {
    public static final int SIGNATURE_MATCH=0, CERT_INPUT_SHA256=1;
    public boolean lyricCertificate=true;
    public int checkSignatures(int uid,int other){return uid==10050?0:-1;}
    public String[] getPackagesForUid(int uid){
        if(uid==10001)return new String[]{"video.a"};
        if(uid==10002)return new String[]{"video.b"};
        if(uid==10003)return new String[]{"com.inkcontrol"};
        return null;
    }
    public boolean hasSigningCertificate(String pkg,byte[] cert,int kind){
        return lyricCertificate && "com.inkcontrol".equals(pkg) && cert.length==32 && kind==CERT_INPUT_SHA256;
    }
}
