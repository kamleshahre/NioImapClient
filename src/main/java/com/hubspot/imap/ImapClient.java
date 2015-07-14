package com.hubspot.imap;

import com.hubspot.imap.imap.ResponseDecoder;
import com.hubspot.imap.imap.command.BaseCommand;
import com.hubspot.imap.imap.command.BlankCommand;
import com.hubspot.imap.imap.command.Command;
import com.hubspot.imap.imap.command.CommandType;
import com.hubspot.imap.imap.command.ListCommand;
import com.hubspot.imap.imap.command.OpenCommand;
import com.hubspot.imap.imap.command.XOAuth2Command;
import com.hubspot.imap.imap.exceptions.AuthenticationFailedException;
import com.hubspot.imap.imap.response.ContinuationResponse;
import com.hubspot.imap.imap.response.ResponseCode;
import com.hubspot.imap.imap.response.events.ByeEvent;
import com.hubspot.imap.imap.response.tagged.ListResponse;
import com.hubspot.imap.imap.response.tagged.OpenResponse;
import com.hubspot.imap.imap.response.tagged.TaggedResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ImapClient extends ChannelDuplexHandler implements AutoCloseable {
  private static final String RESPONSE_DECODER = "response decoder";

  private static final Logger LOGGER = LoggerFactory.getLogger(ImapClient.class);

  private final ImapConfiguration configuration;
  private final Channel channel;
  private final EventExecutorGroup executorGroup;
  private final String userName;
  private final String authToken;

  private final AtomicInteger commandCount;
  private final Promise<Void> loginPromise;

  private final AtomicReference<Command> currentCommand;
  private Promise lastCommandPromise;

  public ImapClient(ImapConfiguration configuration, Channel channel, EventExecutorGroup executorGroup, String userName, String authToken) {
    this.configuration = configuration;
    this.channel = channel;

    this.executorGroup = executorGroup;
    this.userName = userName;
    this.authToken = authToken;

    currentCommand = new AtomicReference<>();
    commandCount = new AtomicInteger(0);
    loginPromise = executorGroup.next().newPromise();

    this.channel.pipeline().addAfter(ImapChannelInitializer.SSL_HANDLER, RESPONSE_DECODER, new ResponseDecoder(this));
    this.channel.pipeline().addLast(new ImapCodec(this));
    this.channel.pipeline().addLast(executorGroup, this);
  }

  public Command getCurrentCommand() {
    return currentCommand.get();
  }

  public Future<TaggedResponse> login() {
    Future<TaggedResponse> loginFuture;
    switch (configuration.getAuthType()) {
      case XOAUTH2:
        loginFuture = oauthLogin();
        break;
      default:
        loginFuture = passwordLogin();
        break;
    }

    loginFuture.addListener(future -> {
      Object response = future.get();
      if (response instanceof ContinuationResponse) {
        loginPromise.setFailure(AuthenticationFailedException.fromContinuation(((ContinuationResponse) response).getMessage()));
      } else {
        TaggedResponse taggedResponse = ((TaggedResponse) response);
        if (taggedResponse.getCode() == ResponseCode.BAD) {
          loginPromise.setFailure(new AuthenticationFailedException(taggedResponse.getMessage()));
        } else {
          loginPromise.setSuccess(null);
        }
      }
    });

    loginPromise.addListener(future -> {
      if (!future.isSuccess()) {
        send(BlankCommand.INSTANCE);
      }
    });

    return loginFuture;
  }

  private Future<TaggedResponse> passwordLogin() {
    return send(new BaseCommand(CommandType.LOGIN, commandCount.getAndIncrement(), userName, authToken));
  }

  private Future<TaggedResponse> oauthLogin() {
    return send(new XOAuth2Command(userName, authToken, commandCount.getAndIncrement()));
  }

  public Future<TaggedResponse> logout() {
    return send(new BaseCommand(CommandType.LOGOUT, commandCount.getAndIncrement()));
  }

  public Future<ListResponse> list(String context, String query) {
    return send(new ListCommand(commandCount.getAndIncrement(), context, query));
  }

  public Future<OpenResponse> open(String folderName, boolean readOnly) {
    return send(new OpenCommand(commandCount.getAndIncrement(), folderName, readOnly));
  }

  public <T extends TaggedResponse> Future<T> noop() {
    return send(CommandType.NOOP);
  }

  public boolean isLoggedIn() {
    return loginPromise.isSuccess() && channel.isOpen();
  }

  public void awaitLogin() throws InterruptedException, ExecutionException {
    loginPromise.get();
  }

  public <T extends TaggedResponse> Future<T> send(CommandType commandType, String... args) {
    BaseCommand baseCommand = new BaseCommand(commandType, commandCount.getAndIncrement(), args);
    return send(baseCommand);
  }

  public synchronized <T extends TaggedResponse> Future<T> send(Command command) {
    final Promise<T> newPromise = executorGroup.next().newPromise();
    if (lastCommandPromise != null) {
      lastCommandPromise.awaitUninterruptibly();
    }
    executorGroup.submit(() -> {
      currentCommand.set(command);
      lastCommandPromise = newPromise;
      channel.writeAndFlush(command);
    });

    return newPromise;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof ContinuationResponse) {
      lastCommandPromise.setSuccess(msg);
    } else if (msg instanceof TaggedResponse) {
      TaggedResponse taggedResponse = ((TaggedResponse) msg);
      lastCommandPromise.setSuccess(taggedResponse);
    }
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof IdleStateEvent) {
      noop();
    } else if (evt instanceof ByeEvent) {
      if (channel.isOpen() && currentCommand.get().getCommandType() != CommandType.LOGOUT) {
        channel.close();
      }
    }

    super.userEventTriggered(ctx, evt);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    LOGGER.error("Error in handler", cause);
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void close() throws Exception {
    if (isLoggedIn()) {
      Future<TaggedResponse> logoutFuture = logout();
      try {
        logoutFuture.get(10, TimeUnit.SECONDS);
      } finally {
        channel.close();
      }
    }
  }
}
