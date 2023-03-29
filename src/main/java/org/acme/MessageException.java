package org.acme;

import org.eclipse.microprofile.reactive.messaging.Message;

public class MessageException extends Throwable{

    private Message<?> msg;

    public MessageException(Message<?> msg){
        this.msg = msg;
    }

    public MessageException(String message, Message<?> msg){
        super(message);
        this.msg = msg;
    }

    public MessageException(String message, Throwable cause, Message<?> msg){
        super(message, cause);
        this.msg = msg;
    }

    public MessageException(Throwable cause, Message<?> msg){
        super(cause);
        this.msg = msg;
    }

    public Message<?> getMsg() {
        return msg;
    }

    public void setMsg(Message<?> msg) {
        this.msg = msg;
    }

    
}