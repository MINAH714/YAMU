// S15P31E103-271. Authenticated administrator console; display configuration only.
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import hudson.model.ListView
import hudson.model.Job
import hudson.views.StatusColumn
import hudson.views.WeatherColumn
import hudson.views.JobColumn
import hudson.views.LastSuccessColumn
import hudson.views.LastFailureColumn
import hudson.views.LastDurationColumn

def instance = Jenkins.get()
def folder = instance.getItemByFullName('yamu', Folder)
if (folder == null) throw new IllegalStateException('Existing yamu folder required')
def directory = new File(instance.rootDir, '.yamu-team-bootstrap')
def jobs = new groovy.json.JsonSlurperClassic().parse(new File(directory, 'catalog.json')).jobs
def allowed = ['ci', 'ci-master', 'cd', 'cd-verification', 'gitlab/mr-ci',
    'gitlab/mr-ci-verification', 'mobile/apk-mock', 'mobile/apk-mock-verification'] as Set
if (jobs*.path.toSet() != allowed || jobs.size() != allowed.size()) {
    throw new IllegalStateException('Unexpected Job catalog')
}
jobs.each { entry ->
    if (!(instance.getItemByFullName('yamu/' + entry.path) instanceof Job)
            || !(entry.group in ['CI', 'CD', 'APK'])) throw new IllegalStateException('Missing or invalid Job')
}
def marker = '[YAMU_TEAM_VIEW:S15P31E103-271]'
def names = ['Team', 'CI', 'CD', 'APK']
// Preflight every name before creating any view; do not overwrite human-owned views.
names.each { name ->
    def existing = folder.getView(name)
    if (existing != null && (!(existing instanceof ListView) || !existing.description?.contains(marker))) {
        throw new IllegalStateException('Unmanaged view name collision: ' + name)
    }
}
def backup = new File(directory, 'previous-primary-view.txt')
if (!backup.exists()) {
    backup.text = folder.primaryView.viewName
    backup.setReadable(false, false); backup.setWritable(false, false)
    backup.setReadable(true, true); backup.setWritable(true, true)
}
def escape = { value -> value.toString().replace('&', '&amp;').replace('<', '&lt;')
    .replace('>', '&gt;').replace('"', '&quot;').replace("'", '&#39;') }
names.each { name ->
    def selected = jobs.findAll { name == 'Team' || it.group == name }
    def view = folder.getView(name)
    if (view == null) {
        view = new ListView(name, folder)
        folder.addView(view)
    }
    view.setRecurse(true)
    view.setJobNames(new TreeSet<String>())
    view.setIncludeRegex(selected.collect { java.util.regex.Pattern.quote(it.path) }.join('|'))
    // No BuildButtonColumn: this entry page is for reading results, not triggering deployment.
    view.setColumns([new StatusColumn(), new WeatherColumn(), new JobColumn(),
        new LastSuccessColumn(), new LastFailureColumn(), new LastDurationColumn()])
    def rows = selected.collect { entry ->
        def job = instance.getItemByFullName('yamu/' + entry.path)
        def url = '/' + job.url
        def links = job.lastBuild == null ? '아직 실행 기록 없음' :
            "<a href='${url}lastBuild/stages/'>단계 그래프</a> · <a href='${url}lastBuild/console'>로그</a>"
        "<tr><td>${escape(entry.group)}</td><td><a href='${url}'>${escape(entry.label)}</a></td>" +
            "<td>${escape(entry.purpose)}</td><td>${links}</td></tr>"
    }.join('\n')
    def html = """<h2>YAMU 팀 빌드·배포 현황</h2>
<p>진행 상황은 <strong>단계 그래프</strong>, 상세 오류는 <strong>로그</strong>를 확인하세요.
아래 Job 목록의 상태·최근 성공·실패·소요 시간은 Jenkins가 실시간으로 표시합니다.</p>
<p><a href='/job/yamu/view/Team/'>전체</a> · <a href='/job/yamu/view/CI/'>CI·MR</a> ·
<a href='/job/yamu/view/CD/'>BE 배포·격리 검증</a> · <a href='/job/yamu/view/APK/'>APK</a></p>
<table><thead><tr><th>구분</th><th>업무</th><th>설명</th><th>최근 실행</th></tr></thead><tbody>${rows}</tbody></table>
<p>비활성 Job도 포함합니다. 과거 성공은 현재 Job 활성화나 실제 운영 배포를 뜻하지 않습니다.
CD 검증은 현재 배포·롤백을 단일 실행 단위로 처리하므로 내부 단계별 실시간 그래프는 후속 작업입니다.
링크 열람에는 기존 Jenkins 권한과 Tailscale 연결이 필요합니다.</p><p>${marker}</p>"""
    def plain = "YAMU 팀 빌드·배포 현황 — ${name}\n" +
        '진행·실패 위치: 아래 Job 클릭 → 실행 번호 클릭 → Pipeline Overview.\n' +
        '탭: Team 전체 / CI 코드·MR 검사 / CD BE 배포·격리 검증 / APK 앱 배포.\n\n' +
        selected.collect { "${it.path} — ${it.label}: ${it.purpose}" }.join('\n') +
        '\n\n비활성 Job도 포함합니다. 과거 성공은 현재 활성화나 실제 배포를 뜻하지 않습니다.\n' +
        'CD 검증 내부 단계는 현재 SSH 단일 실행 단위입니다. 기존 권한·Tailscale 연결이 필요합니다.\n' + marker
    // Respect the existing global markup policy; never enable HTML or change script approvals.
    view.setDescription(instance.markupFormatter.class.name == 'hudson.markup.EscapedMarkupFormatter' ? plain : html)
    view.save()
}
folder.setPrimaryView(folder.getView('Team'))
folder.save()
println('Team / CI / CD / APK views ready. No Job definition, credential, trigger or deployment was changed.')
