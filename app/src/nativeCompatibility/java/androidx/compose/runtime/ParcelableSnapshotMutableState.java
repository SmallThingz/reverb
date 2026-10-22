package androidx.compose.runtime;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Reverb's data-only reader for the released 0.1.2 Parcelable wire name.
 * This is not Compose state: there are no snapshots, observers, setters or UI behavior.
 * Future Reverb state uses its native Bundle schema; only unconsumed old values reserialize here.
 */
public final class ParcelableSnapshotMutableState implements Parcelable {
    private final Object savedValue;
    private final int savedPolicy;

    public ParcelableSnapshotMutableState(Object value, int policy) {
        if (policy < 0 || policy > 2) {
            throw new IllegalStateException("Unsupported MutableState policy " + policy + " was restored");
        }
        savedValue = value;
        savedPolicy = policy;
    }

    public Object getSavedValue() { return savedValue; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel destination, int flags) {
        destination.writeValue(savedValue);
        destination.writeInt(savedPolicy);
    }

    public static final Parcelable.ClassLoaderCreator<ParcelableSnapshotMutableState> CREATOR =
            new LegacyCreator();

    public static final class LegacyCreator
            implements Parcelable.ClassLoaderCreator<ParcelableSnapshotMutableState> {
        @SuppressWarnings("deprecation")
        @Override public ParcelableSnapshotMutableState createFromParcel(Parcel source, ClassLoader loader) {
            Object value = source.readValue(loader != null ? loader : getClass().getClassLoader());
            return new ParcelableSnapshotMutableState(value, source.readInt());
        }

        @Override public ParcelableSnapshotMutableState createFromParcel(Parcel source) {
            return createFromParcel(source, null);
        }

        @Override public ParcelableSnapshotMutableState[] newArray(int size) {
            return new ParcelableSnapshotMutableState[size];
        }
    }
}
