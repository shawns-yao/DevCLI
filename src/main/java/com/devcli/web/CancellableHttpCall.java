package com.devcli.web;

import com.devcli.runtime.CancellationToken;
import com.devcli.tool.ToolExecutionContext;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;

/** OkHttp 调用与工具调用级取消信号之间的适配器。 */
final class CancellableHttpCall {
    private CancellableHttpCall() {
    }

    @FunctionalInterface
    interface ResponseHandler<T> {
        T handle(Response response) throws IOException;
    }

    static <T> T execute(OkHttpClient client, Request request,
                         ToolExecutionContext executionContext,
                         ResponseHandler<T> handler) throws IOException {
        executionContext.throwIfCancelled();
        Call call = client.newCall(request);
        try (CancellationToken.Registration ignored = executionContext
                .cancellationToken().onCancel(cancellation -> call.cancel());
             Response response = call.execute()) {
            executionContext.throwIfCancelled();
            return handler.handle(response);
        } catch (java.util.concurrent.CancellationException e) {
            throw cancelled(executionContext, e);
        } catch (IOException e) {
            if (executionContext.cancellation().isPresent()) {
                throw cancelled(executionContext, e);
            }
            throw e;
        }
    }

    private static InterruptedIOException cancelled(
            ToolExecutionContext context, Exception cause) {
        String message = context.cancellation()
                .map(CancellationToken.Cancellation::message)
                .filter(value -> !value.isBlank())
                .orElse("HTTP 调用已取消");
        InterruptedIOException error = new InterruptedIOException(message);
        error.initCause(cause);
        return error;
    }
}
