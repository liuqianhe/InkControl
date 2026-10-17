package cn.lyric.getter.api.data;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 与 Lyric Getter API 中同名类的 Parcel 格式保持一致（包名、类名必须完全相同），
 * 用于解析 "Lyric_Data" 广播里的 "Data" 字段。
 */
public class LyricData implements Parcelable {
    public static final int TYPE_STOP = 0;
    public static final int TYPE_UPDATE = 1;

    public int type;
    public String lyric;

    private LyricData() {
    }

    public static LyricData of(int type, String lyric) {
        LyricData d = new LyricData();
        d.type = type;
        d.lyric = lyric;
        return d;
    }

    private LyricData(Parcel in) {
        type = in.readInt();
        lyric = in.readString();
        in.readBundle(LyricData.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(lyric);
        dest.writeBundle(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LyricData> CREATOR = new Creator<LyricData>() {
        @Override
        public LyricData createFromParcel(Parcel in) {
            return new LyricData(in);
        }

        @Override
        public LyricData[] newArray(int size) {
            return new LyricData[size];
        }
    };
}
