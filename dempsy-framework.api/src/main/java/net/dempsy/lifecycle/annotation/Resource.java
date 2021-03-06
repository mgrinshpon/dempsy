package net.dempsy.lifecycle.annotation;

public interface Resource extends AutoCloseable {

    /**
     * When an additional reference is made to this instance, this method will be called.
     * If reference counting, this should increment the reference count.
     */
    public void reference();

    /**
     * This method, contrary to what might seem obvious, is balanced with init/reference
     * and NOT to be used to free resource UNLESS the reference count has dropped to zero.
     *
     * Close is NOT idempotent on a Resource. It will be called the same number of times
     * object constuct + init() + reference() are called and should simply close only when
     * the count drops to zero.
     */
    @Override
    public void close();
}
