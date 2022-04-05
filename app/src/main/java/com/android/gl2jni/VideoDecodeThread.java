package com.android.gl2jni;

import android.annotation.TargetApi;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VideoDecodeThread extends Thread {
    Context context;
    MediaExtractor extractor;
    MediaCodec decoder;
    boolean isStop = false;
    private int mPreviewWidth = 0;
    private int mPreviewHeight = 0;
    byte[][] mYUVBytes;
    int[] mRGBBytes;
    Bitmap mRGBFrameBitmap;
    public static final String VIDEO = "video/";
    private static final String TAG = VideoDecodeThread.class.getName();
    FrameQueue mVideoFrameQueue;

    public VideoDecodeThread(Context mContext, FrameQueue videoFrameQueue) {
        context = mContext;
        mVideoFrameQueue = videoFrameQueue;
    }

    @Override
    public void run() {
        super.run();

        isStop = false;
        String inputFile = getFilePath();

        Log.d(TAG,"filePath : "+inputFile);

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile);

            int index = selectTrack(extractor);
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);

            if (mime != null) {
                if (mime.startsWith(VIDEO)) {
                    extractor.selectTrack(index);
                    decoder = MediaCodec.createDecoderByType(mime);
                    try {
                        Log.d(TAG, "format : " + format);
                        decoder.configure(format, null, null, 0 /* Decode */);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "codec $mime failed configuration" + e.getMessage());
                    }
                    decoder.start();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        Bitmap finalBitmap = null;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        ByteBuffer[] inputBufferes = decoder.getInputBuffers();
        while (!isStop){
            int index = decoder.dequeueInputBuffer(1000);
            Log.d(TAG,"decoder Dequeue InputBuffer");
            if (index>0) {
                ByteBuffer inputBuffer = inputBufferes[index];
                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                if (extractor.advance() && sampleSize > 0) {
                    decoder.queueInputBuffer(index, 0, sampleSize, extractor.getSampleTime(), 0);
                } else {
                    Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } else {
                Log.d(TAG,"Failed getting input buffers : " +index);
            }

            int outIndex = decoder.dequeueOutputBuffer(bufferInfo,1000);
            Log.d(TAG,"decoder Dequeue OutputBuffer");
            switch (outIndex){
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED format : " + decoder.getOutputFormat());
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    Log.d(TAG, "INFO_TRY_AGAIN_LATER");
                default:
                    if (outIndex>0){
                        Image image = decoder.getOutputImage(outIndex);
                        Image.Plane[] plans = image.getPlanes();
                        try {
                            if (mPreviewWidth != image.getWidth() || mPreviewHeight != image.getHeight()) {
                                mPreviewWidth = image.getWidth();
                                mPreviewHeight = image.getHeight();
                                Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWidth, mPreviewHeight));
                                mRGBBytes = new int[mPreviewWidth * mPreviewHeight];
                                mRGBFrameBitmap = Bitmap.createBitmap(mPreviewWidth, mPreviewHeight, Bitmap.Config.ARGB_8888);
                                mYUVBytes = new byte[plans.length][];
                                for (int i=0;i< plans.length;i++) {
//                                    mYUVBytes[i] = ByteArray(planes[i].buffer.capacity())
                                    mYUVBytes[i] = new byte[plans[i].getBuffer().capacity()];
                                }
                            }
                            for (int i=0;i< plans.length;i++) {
                                plans[i].getBuffer().get(mYUVBytes[i]);
                            }
                            int yRowStride = plans[0].getRowStride();
                            int uvRowStride = plans[1].getRowStride();
                            int uvPixelStride = plans[1].getPixelStride();

                            ImageUtils.convertYUV420ToARGB8888(
                                    mYUVBytes[0],
                                    mYUVBytes[1],
                                    mYUVBytes[2],
                                    mPreviewWidth,
                                    mPreviewHeight,
                                    yRowStride,
                                    uvRowStride,
                                    uvPixelStride,
                                    mRGBBytes
                            );
                            image.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.d(TAG,"Exception error :"+e.getMessage());
                        }
                        mRGBFrameBitmap.setPixels(
                                mRGBBytes,
                                0,
                                mPreviewWidth,
                                0,
                                0,
                                mPreviewWidth,
                                mPreviewHeight
                        );
                        finalBitmap = mRGBFrameBitmap;
                        try {
                            mVideoFrameQueue.push(finalBitmap);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        decoder.releaseOutputBuffer(outIndex, true /* Surface init */);
                    }

//                    extractor.advance();
            }

        }

        decoder.stop();
        decoder.release();
        extractor.release();
    }

    private int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i=0;i<numTracks;i++){
            MediaFormat format = extractor.getTrackFormat(i);
            String mime =format.getString(MediaFormat.KEY_MIME);
            if (mime != null) {
                if (mime.startsWith("video/")) {
//                if (VERBOSE) {
//                Log.d(TAG, "Extractor selected track $i ($mime): $format");
                    Log.d(TAG, "Extractor selected track &s,&s,&s " + i + " " + mime + " " + format);
//                }
                    return i;
                }
            }
        }
        return -1;
    }

    private String getFilePath() {
        String videoPath = null;
        Uri contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] selectionArgs = { Environment.DIRECTORY_DOCUMENTS.toString() + "/Video/" };
        Cursor cursor = context.getContentResolver().query(contentUri,null,selection,selectionArgs,null);
        Uri uri = null;
        if (cursor != null){
            if (cursor.getCount()==0){
                Toast.makeText(
                        context,
                        "No file found in \"" + Environment.DIRECTORY_DOCUMENTS + "/Video/\"",
                        Toast.LENGTH_LONG
                ).show();
            } else {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
                    uri = ContentUris.withAppendedId(contentUri, id);
                    try {
                        videoPath = PathUtil.getPath(context, uri);
                    } catch (URISyntaxException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return videoPath;
    }
}
