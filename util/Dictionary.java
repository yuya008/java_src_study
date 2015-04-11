
package java.util;

public abstract
class Dictionary<K,V> {
    public Dictionary() {
    }

    abstract public int size();

    abstract public boolean isEmpty();

    abstract public Enumeration<K> keys();

    abstract public Enumeration<V> elements();

    abstract public V get(Object key);

    abstract public V put(K key, V value);

    abstract public V remove(Object key);
}
