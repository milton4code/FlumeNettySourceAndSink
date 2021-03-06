package com.xiyuan.flume.sink;

import com.google.common.collect.Lists;
import com.xiyuan.flume.serialization.HeaderAndBodySerialization;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by xiyuan_fengyu on 2017/12/12 15:07.
 */
public class NettyServerSink extends AbstractSink implements Configurable {

    private final Logger logger = LoggerFactory.getLogger(NettyServerSink.class);

    private String host;

    private int port;

    private String users;

    private SinkCounter sinkCounter;

    private NettyServer nettyServer;

    private int batchSize;

    private long transactionTime;

    @Override
    public void configure(Context context) {
        host = context.getString("host", "0.0.0.0");
        port = context.getInteger("port", 9090);
        users = context.getString("users", "user_default");
        batchSize = context.getInteger("batchSize", 128);
        transactionTime = context.getLong("transactionTime", 5000L);
    }

    @Override
    public synchronized void start() {
        nettyServer = new NettyServer(host, port, users);
        this.sinkCounter = new SinkCounter(this.getName());
        this.sinkCounter.start();
        super.start();
        logger.info(NettyServerSink.class.getSimpleName() + " Started");
    }

    @Override
    public synchronized void stop() {
        super.stop();
        this.sinkCounter.stop();
        nettyServer.shutdown();
        logger.info(NettyServerSink.class.getSimpleName() + " Stopped");
    }

    private long lastTranscationCommitTime = System.currentTimeMillis();

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.READY;
        Channel channel = this.getChannel();
        Transaction transaction = channel.getTransaction();
        try {
            transaction.begin();
            List<Event> batch = Lists.newLinkedList();

            int size;
            for(size = 0; size < this.batchSize && System.currentTimeMillis() - lastTranscationCommitTime < transactionTime; ++size) {
                Event event = channel.take();
                if (event == null) {
                    break;
                }

//                logger.info(NettyServerSink.class.getSimpleName() + " add event to batch");
                batch.add(event);
            }

            size = batch.size();
            if (size == 0) {
                this.sinkCounter.incrementBatchEmptyCount();
                status = Status.BACKOFF;
            } else {
                if (size < batchSize) {
                    this.sinkCounter.incrementBatchUnderflowCount();
                } else {
                    this.sinkCounter.incrementBatchCompleteCount();
                }

                this.sinkCounter.addToEventDrainAttemptCount((long)size);
                sendBatch(batch);
//                logger.info(NettyServerSink.class.getSimpleName() + " send batch");
            }

            transaction.commit();
            lastTranscationCommitTime = System.currentTimeMillis();
            this.sinkCounter.addToEventDrainSuccessCount((long)size);
        } catch (Throwable var10) {
            transaction.rollback();
            var10.printStackTrace();
            status = Status.BACKOFF;
        } finally {
            transaction.close();
        }

        return status;
    }

    private void sendBatch(List<Event> batch) {
        List<ByteBuffer> buffers = new ArrayList<>();
        for (Event event : batch) {
            buffers.add(HeaderAndBodySerialization.serialize(event));
        }
        nettyServer.broadcast(buffers);
    }



}
