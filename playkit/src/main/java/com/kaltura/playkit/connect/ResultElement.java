package com.kaltura.playkit.connect;

/**
 * Created by tehilarozin on 06/09/2016.
 */
public interface ResultElement<T> {

    T getResponse();

    boolean isSuccess();

    ErrorElement getError();

}
