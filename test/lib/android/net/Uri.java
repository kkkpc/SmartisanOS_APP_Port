package android.net;
public class Uri {
    public static final class Builder {
        public Builder appendPath(String s) { return this; }
        public Builder appendQueryParameter(String k, String v) { return this; }
    }
    public Builder buildUpon() { return new Builder(); }
}
