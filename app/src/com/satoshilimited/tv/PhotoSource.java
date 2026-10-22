package com.satoshilimited.tv;

interface PhotoSource {
    void next(int dir, ArcaHub.Callback<PhotoStream.Photo> callback);

    void close();
}
