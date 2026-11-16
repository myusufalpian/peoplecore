package id.mydev.peoplecore.notification.domain;

public class InvitationReissueRequiredException extends IllegalArgumentException {
    public InvitationReissueRequiredException() {
        super("Invitation destination repair requires authorized identity reissue");
    }
}
