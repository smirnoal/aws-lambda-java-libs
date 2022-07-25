package com.amazonaws.services.lambda.runtime.api.client.runtimeapi;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;

/**
 * LambdaRuntimeClient is a client of the AWS Lambda Runtime HTTP API for custom runtimes.
 * <p>
 * API definition can be found at https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html
 * <p>
 * Copyright (c) 2019 Amazon. All rights reserved.
 */
public class LambdaRuntimeClient {

    private static final String INVOCATION_ERROR_URL_TEMPLATE = "http://%s/2018-06-01/runtime/invocation/%s/error";
    private static final String INVOCATION_SUCCESS_URL_TEMPLATE = "http://%s/2018-06-01/runtime/invocation/%s/response";
    private static final String NEXT_URL_TEMPLATE = "http://%s/2018-06-01/runtime/invocation/next";
    private static final String INIT_ERROR_URL_TEMPLATE = "http://%s/2018-06-01/runtime/init/error";

    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private static final String XRAY_ERROR_CAUSE_HEADER = "Lambda-Runtime-Function-XRay-Error-Cause";
    private static final String ERROR_TYPE_HEADER = "Lambda-Runtime-Function-Error-Type";

    private static final String REQUEST_ID_HEADER = "lambda-runtime-aws-request-id";
    private static final String FUNCTION_ARN_HEADER = "lambda-runtime-invoked-function-arn";
    private static final String DEADLINE_MS_HEADER = "lambda-runtime-deadline-ms";
    private static final String TRACE_ID_HEADER = "lambda-runtime-trace-id";
    private static final String CLIENT_CONTEXT_HEADER = "lambda-runtime-client-context";
    private static final String COGNITO_IDENTITY_HEADER = "lambda-runtime-cognito-identity";

    private static final String USER_AGENT = String.format(
            "aws-lambda-java/%s",
            System.getProperty("java.vendor.version"));

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Integer.MAX_VALUE, TimeUnit.MILLISECONDS)
            .writeTimeout(Integer.MAX_VALUE, TimeUnit.MILLISECONDS)
            .callTimeout(Integer.MAX_VALUE, TimeUnit.MILLISECONDS)
            .connectTimeout(Integer.MAX_VALUE, TimeUnit.MILLISECONDS)
            .build();

    private final String hostnamePort;
    private final Request nextRequest;


    public LambdaRuntimeClient(String hostnamePort) {
        Objects.requireNonNull(hostnamePort, "hostnamePort cannot be null");
        this.hostnamePort = hostnamePort;
        nextRequest = new Request.Builder()
                .url(String.format(NEXT_URL_TEMPLATE, hostnamePort))
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
    }

    public InvocationRequest waitForNextInvocation() {
        try (Response response = HTTP_CLIENT.newCall(nextRequest).execute()) {
            return invocationRequestFromHttpResponse(response);
        } catch (IOException e) {
            throw new LambdaRuntimeClientException("Failed to get next invoke", e);
        }
    }

    private InvocationRequest invocationRequestFromHttpResponse(Response response) throws IOException {
        InvocationRequest result = new InvocationRequest();

        result.id = response.headers().get(REQUEST_ID_HEADER);
        if (result.id == null) {
            throw new LambdaRuntimeClientException("Request ID absent");
        }

        result.invokedFunctionArn = response.headers().get(FUNCTION_ARN_HEADER);
        if (result.invokedFunctionArn == null) {
            throw new LambdaRuntimeClientException("Function ARN absent");
        }

        String deadlineMs = response.headers().get(DEADLINE_MS_HEADER);
        result.deadlineTimeInMs = deadlineMs == null ? 0 : Long.parseLong(deadlineMs);

        result.xrayTraceId = response.headers().get(TRACE_ID_HEADER);
        result.clientContext = response.headers().get(CLIENT_CONTEXT_HEADER);
        result.cognitoIdentity = response.headers().get(COGNITO_IDENTITY_HEADER);

        result.content = Objects.requireNonNull(response.body()).bytes();

        return result;
    }

    public void postInvocationSuccess(String requestId, byte[] payload) {
        Request invocationResponseRequest = new Request.Builder()
                .url(String.format(INVOCATION_SUCCESS_URL_TEMPLATE, hostnamePort, requestId))
                .header("User-Agent", USER_AGENT)
                .post(RequestBody.create(payload))
                .build();

        try (Response response = HTTP_CLIENT.newCall(invocationResponseRequest).execute()) {
        } catch (IOException e) {
            throw new LambdaRuntimeClientException("Failed to post invocation result", e);
        }
    }

    public void postInvocationError(InvocationError invocationError) {
        String endpoint = String.format(INVOCATION_ERROR_URL_TEMPLATE, hostnamePort, invocationError.getId());
        post(endpoint, invocationError);
    }

    public void postInitError(InvocationError invocationError) {
        String endpoint = String.format(INIT_ERROR_URL_TEMPLATE, hostnamePort);
        post(endpoint, invocationError);
    }

    private void post(String endpoint, InvocationError invocationError) {

        Request.Builder request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(invocationError.getErrorResponse()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", DEFAULT_CONTENT_TYPE);

        if (invocationError.getErrorType().isPresent()) {
            request.header(ERROR_TYPE_HEADER, invocationError.getErrorType().get());
        }
        if (invocationError.getErrorCause().isPresent()) {
            request.header(XRAY_ERROR_CAUSE_HEADER, invocationError.getErrorCause().get());
        }

        try (Response response = HTTP_CLIENT.newCall(request.build()).execute()) {
            if (response.code() != HTTP_ACCEPTED) {
                throw new LambdaRuntimeClientException(
                        String.format("%s Response code: '%d'.", endpoint, response.code()));
            }
        } catch (IOException e) {
            throw new LambdaRuntimeClientException("Failed to post error", e);
        }
    }
}
