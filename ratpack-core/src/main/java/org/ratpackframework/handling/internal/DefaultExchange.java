/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ratpackframework.handling.internal;

import io.netty.channel.ChannelHandlerContext;
import org.ratpackframework.handling.ByAcceptsResponder;
import org.ratpackframework.handling.ByMethodResponder;
import org.ratpackframework.registry.Registry;
import org.ratpackframework.server.RatpackServerSettings;
import org.ratpackframework.registry.internal.ObjectHoldingHierarchicalRegistry;
import org.ratpackframework.error.ClientErrorHandler;
import org.ratpackframework.error.ServerErrorHandler;
import org.ratpackframework.file.FileSystemBinding;
import org.ratpackframework.handling.Exchange;
import org.ratpackframework.handling.Handler;
import org.ratpackframework.http.Request;
import org.ratpackframework.http.Response;
import org.ratpackframework.path.PathBinding;

import java.io.File;
import java.util.List;
import java.util.Map;

public class DefaultExchange implements Exchange {

  private final Request request;
  private final Response response;

  private final RatpackServerSettings serverSettings;
  private final ChannelHandlerContext channelHandlerContext;

  private final Handler next;
  private final Registry registry;

  public DefaultExchange(Request request, Response response, RatpackServerSettings serverSettings, ChannelHandlerContext channelHandlerContext, Registry registry, Handler next) {
    this.request = request;
    this.response = response;
    this.serverSettings = serverSettings;
    this.channelHandlerContext = channelHandlerContext;
    this.registry = registry;
    this.next = next;
  }

  public Request getRequest() {
    return request;
  }

  public Response getResponse() {
    return response;
  }

  public <T> T get(Class<T> type) {
    return registry.get(type);
  }

  public <T> T maybeGet(Class<T> type) {
    return registry.maybeGet(type);
  }

  public void next() {
    next.handle(this);
  }

  public void insert(List<Handler> handlers) {
    doNext(this, registry, handlers, 0, next);
  }

  public void insert(Registry registry, List<Handler> handlers) {
    doNext(this, registry, handlers, 0, next);
  }

  public <P, T extends P> void insert(Class<P> publicType, T implementation, List<Handler> handlers) {
    doNext(this, new ObjectHoldingHierarchicalRegistry(registry, publicType, implementation), handlers, 0, next);
  }

  public void insert(Object object, List<Handler> handlers) {
    doNext(this, new ObjectHoldingHierarchicalRegistry(registry, object), handlers, 0, next);
  }

  public Map<String, String> getPathTokens() {
    return get(PathBinding.class).getTokens();
  }

  public Map<String, String> getAllPathTokens() {
    return get(PathBinding.class).getAllTokens();
  }

  public File file(String path) {
    return get(FileSystemBinding.class).file(path);
  }

  public void error(Exception exception) {
    ServerErrorHandler serverErrorHandler = get(ServerErrorHandler.class);
    serverErrorHandler.error(this, exception);
  }

  public void clientError(int statusCode) {
    get(ClientErrorHandler.class).error(this, statusCode);
  }

  public void withErrorHandling(Runnable runnable) {
    try {
      runnable.run();
    } catch (Exception e) {
      if (e instanceof HandlerException) {
        ((HandlerException) e).getExchange().error((Exception) e.getCause());
      } else {
        error(e);
      }
    }
  }

  public ByMethodResponder getMethods() {
    return new DefaultByMethodResponder(this);
  }

  public ByAcceptsResponder getAccepts() {
    return new DefaultByAcceptsResponder(this);
  }

  protected void doNext(final Exchange parentExchange, final Registry registry, final List<Handler> handlers, final int index, final Handler exhausted) {
    assert registry != null;
    if (index == handlers.size()) {
      try {
        exhausted.handle(parentExchange);
      } catch (Exception e) {
        if (e instanceof HandlerException) {
          throw (HandlerException) e;
        } else {
          throw new HandlerException(this, e);
        }
      }

    } else {
      Handler handler = handlers.get(index);
      Handler nextHandler = new Handler() {
        public void handle(Exchange exchange) {
          ((DefaultExchange) exchange).doNext(parentExchange, registry, handlers, index + 1, exhausted);
        }
      };
      DefaultExchange childExchange = new DefaultExchange(request, response, serverSettings, channelHandlerContext, registry, nextHandler);
      try {
        handler.handle(childExchange);
      } catch (Exception e) {
        if (e instanceof HandlerException) {
          throw (HandlerException) e;
        } else {
          throw new HandlerException(childExchange, e);
        }
      }
    }
  }

}
