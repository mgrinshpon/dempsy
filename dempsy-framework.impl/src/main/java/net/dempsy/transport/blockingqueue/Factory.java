package net.dempsy.transport.blockingqueue;

import net.dempsy.Locator;
import net.dempsy.transport.SenderFactory;

public class Factory implements Locator {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T locate(final Class<T> clazz) {
        if(SenderFactory.class.equals(clazz))
            return (T)new BlockingQueueSenderFactory();
        return null;
    }

}
