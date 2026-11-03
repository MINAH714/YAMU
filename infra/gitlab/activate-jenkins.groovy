// S15P31E103-268. Execute only AFTER human merge into develop and a successful pilot.
import jenkins.model.Jenkins
def instance = Jenkins.get()
def check = instance.getItemByFullName('yamu/gitlab/mr-ci-verification')
def main = instance.getItemByFullName('yamu/gitlab/mr-ci')
if (check == null || main == null || check.isBuilding() || check.lastCompletedBuild?.result?.toString() != 'SUCCESS') {
    throw new IllegalStateException('Successful completed pilot required')
}
if (!main.description?.contains('Managed GitLab MR pipeline / S15P31E103-268')
        || main.definition.scm.branches*.name != ['*/develop']) throw new IllegalStateException('Unexpected main job')
if (main.isDisabled()) {
    main.setDisabled(false)
    main.save()
    main.scheduleBuild2(0)
}
println('MR polling job enabled. Human merge is a prerequisite. No deployment was triggered.')
