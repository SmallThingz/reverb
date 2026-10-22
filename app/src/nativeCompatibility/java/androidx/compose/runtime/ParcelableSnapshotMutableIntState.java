package androidx.compose.runtime;

import android.os.Parcel;
import android.os.Parcelable;

/** Data-only compatibility reader for the released 0.1.2 Int Parcelable wire name. */
public final class ParcelableSnapshotMutableIntState implements Parcelable {
    private final int savedValue;

    public ParcelableSnapshotMutableIntState(int value) { savedValue = value; }

    public int getSavedValue() { return savedValue; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(savedValue);
    }

    public static final Parcelable.Creator<ParcelableSnapshotMutableIntState> CREATOR =
            new LegacyCreator();

    public static final class LegacyCreator
            implements Parcelable.Creator<ParcelableSnapshotMutableIntState> {
        @Override public ParcelableSnapshotMutableIntState createFromParcel(Parcel source) {
            return new ParcelableSnapshotMutableIntState(source.readInt());
        }

        @Override public ParcelableSnapshotMutableIntState[] newArray(int size) {
            return new ParcelableSnapshotMutableIntState[size];
        }
    }
}
