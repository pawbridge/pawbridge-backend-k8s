package com.pawbridge.backend.user.internal.animalactivity.application;

public final class FavoriteActivityRejectedException extends RuntimeException {
    public enum Reason {
        ALREADY_EXISTS(409,"이미 찜한 동물입니다."), NOT_FOUND(404,"찜 내역을 찾을 수 없습니다."), ANIMAL_NOT_FOUND(404,"동물을 찾을 수 없습니다");
        private final int status;private final String message;
        Reason(int status,String message){this.status=status;this.message=message;}
        public int status(){return status;}public String message(){return message;}
    }
    private final Reason reason;
    public FavoriteActivityRejectedException(Reason reason){super(reason.message());this.reason=reason;}
    public Reason reason(){return reason;}
}
