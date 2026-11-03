// Run only AFTER a human merges S15P31E103-263 and the verification job succeeds.
import jenkins.model.Jenkins
import hudson.triggers.SCMTrigger
def instance = Jenkins.get()
def verification = instance.getItemByFullName('yamu/mobile/apk-mock-verification')
if (verification?.lastCompletedBuild?.result?.toString() != 'SUCCESS') {
    throw new IllegalStateException('The latest APK verification build must succeed first')
}
def job = instance.getItemByFullName('yamu/mobile/apk-mock')
if (job == null || !job.description?.contains('S15P31E103-263')
        || job.definition.scriptPath != 'infra/android/Jenkinsfile'
        || job.definition.scm.branches*.name != ['*/develop']) {
    throw new IllegalStateException('Unexpected develop APK job')
}
if (job.isBuilding()) throw new IllegalStateException('Do not reconfigure a running APK job')
job.setDisabled(false)
job.addTrigger(new SCMTrigger('H/5 * * * *'))
job.save()
if (!job.isInQueue()) job.scheduleBuild2(0)
println('Develop APK job enabled and queued; subsequent matching source changes poll every five minutes.')
