package android.database;

public interface Cursor {
    int getCount();
    boolean moveToFirst();
    boolean moveToNext();
    String getString(int columnIndex);
    int getInt(int columnIndex);
    long getLong(int columnIndex);
    int getColumnIndex(String columnName);
    void close();
    boolean isClosed();
}
