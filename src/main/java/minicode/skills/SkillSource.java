package minicode.skills;

public enum SkillSource {
    PROJECT_JAVA("project_java"),
    USER_JAVA("user_java"),
    PROJECT_TS("project_ts"),
    USER_TS("user_ts"),
    COMPAT_PROJECT("compat_project"),
    COMPAT_USER("compat_user");

    private final String label;

    SkillSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
