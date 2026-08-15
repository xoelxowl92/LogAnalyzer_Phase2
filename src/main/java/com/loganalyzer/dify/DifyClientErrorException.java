package com.loganalyzer.dify;

/**
 * 재시도로 해결되지 않는 Dify 호출 실패 시 발생 (4xx 클라이언트 오류, 워크플로우 내부 실행 실패 등).
 * 동일한 요청을 다시 보내도 같은 결과가 나오므로 재시도 없이 즉시 중단해야 한다.
 */
public class DifyClientErrorException extends DifyApiException {

    public DifyClientErrorException(String message) {
        super(message);
    }

    public DifyClientErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
