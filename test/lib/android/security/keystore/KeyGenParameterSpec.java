package android.security.keystore;
public final class KeyGenParameterSpec {
    public static final class Builder {
        public Builder(String alias, int purposes) { }
        public Builder setBlockModes(String m) { return this; }
        public Builder setEncryptionPaddings(String p) { return this; }
        public Builder setKeySize(int s) { return this; }
        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(); }
    }
}
