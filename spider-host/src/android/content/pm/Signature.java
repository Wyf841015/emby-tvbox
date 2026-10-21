package android.content.pm;

public class Signature {
    private final String value;
    public Signature(byte[] sig) { this.value = java.util.Arrays.toString(sig); }
    public Signature(String s) { this.value = s; }
    public String toCharsString() { return value; }
    @Override public String toString() { return value; }
    @Override public int hashCode() { return value == null ? 0 : value.hashCode(); }
    @Override public boolean equals(Object o) { return o instanceof Signature && toString().equals(o.toString()); }
}