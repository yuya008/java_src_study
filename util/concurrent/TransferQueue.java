

package java.util.concurrent;

public interface TransferQueue<E> extends BlockingQueue<E> {
    boolean tryTransfer(E e);

    void transfer(E e) throws InterruptedException;

    boolean tryTransfer(E e, long timeout, TimeUnit unit)
        throws InterruptedException;

    boolean hasWaitingConsumer();

    int getWaitingConsumerCount();
}
