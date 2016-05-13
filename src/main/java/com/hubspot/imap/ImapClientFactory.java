package com.hubspot.imap;

import com.google.common.base.Throwables;
import com.hubspot.imap.client.ImapClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

public class ImapClientFactory implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImapClientFactory.class);

  private final ImapConfiguration configuration;
  private final Bootstrap bootstrap;
  private final EventLoopGroup eventLoopGroup;
  private final EventExecutorGroup promiseExecutorGroup;
  private final EventExecutorGroup idleExecutorGroup;

  public ImapClientFactory(ImapConfiguration configuration) {
    this.configuration = configuration;
    this.bootstrap = new Bootstrap();

    if (configuration.getUseEpoll()) {
      LOGGER.info("Using epoll eventloop");
      this.eventLoopGroup = new EpollEventLoopGroup(configuration.getNumEventLoopThreads());
    } else {
      this.eventLoopGroup = new NioEventLoopGroup(configuration.getNumEventLoopThreads());
    }

    this.promiseExecutorGroup = new DefaultEventExecutorGroup(configuration.getNumExecutorThreads());
    this.idleExecutorGroup = new DefaultEventExecutorGroup(4);

    SslContext context = null;
    if (configuration.getUseSslConnect()) {
      try {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(((KeyStore) null));

        context = SslContextBuilder.forClient()
          .trustManager(trustManagerFactory)
          .build();
      } catch (NoSuchAlgorithmException | SSLException | KeyStoreException e) {
        throw Throwables.propagate(e);
      }
    }

    bootstrap.group(eventLoopGroup)
        .option(ChannelOption.SO_LINGER, 0)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, configuration.getConnectTimeoutMillis())
        .option(ChannelOption.SO_KEEPALIVE, false)
        .option(ChannelOption.AUTO_CLOSE, true)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 32 * 1024)
        .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024)
        .handler(configuration.getUseSslConnect() ? new ImapChannelInitializer(configuration) : new ImapChannelInitializer(context, configuration));

    if (configuration.getUseEpoll()) {
      bootstrap.channel(EpollSocketChannel.class);
    } else {
      bootstrap.channel(NioSocketChannel.class);
    }
  }

  public ImapClient connect(String userName, String oauthToken) throws InterruptedException {
    ImapClient client = new ImapClient(configuration, bootstrap, promiseExecutorGroup, idleExecutorGroup, userName, oauthToken);
    client.connect();
    return client;
  }

  @Override
  public void close() {
    promiseExecutorGroup.shutdownGracefully();
    idleExecutorGroup.shutdownGracefully();
    eventLoopGroup.shutdownGracefully();
  }
}
