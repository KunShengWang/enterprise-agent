package com.agent.platform.common;

import com.agent.platform.runtime.AgentSessionBusyException;
import com.agent.platform.workbench.persistence.WorkbenchAccessDeniedException;
import com.agent.platform.workbench.persistence.WorkbenchCasConflictException;
import com.agent.platform.workbench.persistence.WorkbenchIdempotencyConflictException;
import com.agent.platform.workbench.persistence.WorkbenchNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, ServerWebInputException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(Exception exception) {
        return ApiResponse.failure(ErrorCode.BAD_REQUEST, safeMessage(exception));
    }

    @ExceptionHandler(AgentSessionBusyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(AgentSessionBusyException exception) {
        return ApiResponse.failure(ErrorCode.CONFLICT, safeMessage(exception));
    }

    @ExceptionHandler(WorkbenchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleWorkbenchNotFound(WorkbenchNotFoundException exception) {
        return ApiResponse.failure(ErrorCode.NOT_FOUND, "resource not found");
    }

    @ExceptionHandler(WorkbenchAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleWorkbenchForbidden(WorkbenchAccessDeniedException exception) {
        return new ApiResponse<>(false, "FORBIDDEN", "operation is not permitted", null);
    }

    @ExceptionHandler({WorkbenchCasConflictException.class, WorkbenchIdempotencyConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleWorkbenchConflict(RuntimeException exception) {
        return ApiResponse.failure(ErrorCode.CONFLICT, safeMessage(exception));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleInternalError(Exception exception) {
        return ApiResponse.failure(ErrorCode.INTERNAL_ERROR, safeMessage(exception));
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().isBlank()) {
            return "request failed";
        }
        return exception.getMessage();
    }
}
