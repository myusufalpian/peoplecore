package id.mydev.peoplecore.notification.domain;

import id.mydev.peoplecore.notification.domain.NotificationModels.Message;

public interface EmailProvider {
    String send(Message message);
    final class DefinitelyUnsentException extends RuntimeException {
        private final boolean retryable;
        public DefinitelyUnsentException(boolean retryable) {
            super("Provider rejected the notification");
            this.retryable = retryable;
        }
        public boolean retryable() { return retryable; }
    }
}
