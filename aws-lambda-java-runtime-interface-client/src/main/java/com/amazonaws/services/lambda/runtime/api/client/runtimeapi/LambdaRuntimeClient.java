package com.amazonaws.services.lambda.runtime.api.client.runtimeapi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

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

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofDays(1))
            .build();

    private final String hostnamePort;
    private final HttpRequest nextRequest;


    public LambdaRuntimeClient(String hostnamePort) {
        Objects.requireNonNull(hostnamePort, "hostnamePort cannot be null");
        this.hostnamePort = hostnamePort;
        nextRequest = HttpRequest.newBuilder(URI.create(String.format(NEXT_URL_TEMPLATE, hostnamePort)))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }

    public InvocationRequest waitForNextInvocation() {
        HttpResponse<byte[]> response;
        try {
            response = HTTP_CLIENT.send(nextRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new LambdaRuntimeClientException("Failed to get next invoke", e);
        }

        return invocationRequestFromHttpResponse(response);
    }

    private InvocationRequest invocationRequestFromHttpResponse(HttpResponse<byte[]> response) {
        InvocationRequest result = new InvocationRequest();

        result.id = response.headers().firstValue(REQUEST_ID_HEADER).orElseThrow(
                () -> new LambdaRuntimeClientException("Request ID absent"));
        result.invokedFunctionArn = response.headers().firstValue(FUNCTION_ARN_HEADER).orElseThrow(
                () -> new LambdaRuntimeClientException("Function ARN absent"));
        result.deadlineTimeInMs = Long.parseLong(response.headers().firstValue(DEADLINE_MS_HEADER).orElse("0"));
        result.xrayTraceId = response.headers().firstValue(TRACE_ID_HEADER).orElse(null);
        result.clientContext = response.headers().firstValue(CLIENT_CONTEXT_HEADER).orElse(null);
        result.cognitoIdentity = response.headers().firstValue(COGNITO_IDENTITY_HEADER).orElse(null);
        result.content = response.body();

        return result;
    }

    public void postInvocationSuccess(String requestId, byte[] response) {
        URI endpoint = URI.create(String.format(INVOCATION_SUCCESS_URL_TEMPLATE, hostnamePort, requestId));
        HttpRequest invocationResponseRequest = HttpRequest.newBuilder(endpoint)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(response))
                .build();

        try {
            HTTP_CLIENT.send(invocationResponseRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new LambdaRuntimeClientException("Failed to post invocation result", e);
        }
    }

    public void postInvocationError(InvocationError invocationError) {
        URI endpoint = URI.create(String.format(INVOCATION_ERROR_URL_TEMPLATE, hostnamePort, invocationError.getId()));
        post(endpoint, invocationError);
    }

    public void postInitError(InvocationError invocationError) {
        URI endpoint = URI.create(String.format(INIT_ERROR_URL_TEMPLATE, hostnamePort));
        post(endpoint, invocationError);
    }

    private void post(URI endpoint, InvocationError invocationError) {

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofByteArray(invocationError.getErrorResponse()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", DEFAULT_CONTENT_TYPE);

        if (invocationError.getErrorType().isPresent()) {
            request.header(ERROR_TYPE_HEADER, invocationError.getErrorType().get());
        }
        if (invocationError.getErrorCause().isPresent()) {
            request.header(XRAY_ERROR_CAUSE_HEADER, invocationError.getErrorCause().get());
        }

        HttpResponse<Void> response;
        try {
            response = HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException | IOException e) {
            throw new LambdaRuntimeClientException("Failed to post error", e);
        }

        if (response.statusCode() != HTTP_ACCEPTED) {
            throw new LambdaRuntimeClientException(
                    String.format("%s Response code: '%d'.", endpoint, response.statusCode()));
        }
    }
}
