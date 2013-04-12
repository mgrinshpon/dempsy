/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nokia.dempsy.messagetransport;

import com.nokia.dempsy.message.MessageBufferOutput;

/**
 * Abstraction to create multiple sender based on destination.
 */
public interface SenderFactory
{

   public Sender getSender(Destination destination) throws MessageTransportException;
   
   /**
    * <p>shutdown() must be implemented such that it doesn't throw an exception no matter what
    * but forces the stopping of any underlying resources that require stopping. Stop
    * is expected to stop Senders that it created.</p>
    * 
    * <p>Dempsy will call this method on shutdown of the container.</p>
    * 
    * <p>NOTE: shutdown() must be idempotent.</p>
    */
   public void shutdown();
   
   /**
    * Dempsy will invoke this method when the destination in question will no longer
    * have messages sent to it. Any queued messages need appropriate disposition.
    */
   public void stopDestination(Destination destination);
   
   /**
    * This method will provide the place to put the message that's going to be sent.
    */
   public MessageBufferOutput prepareMessage();
   
}