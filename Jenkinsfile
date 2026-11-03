pipeline {
    agent { label 'yamu-ci' }

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
        timestamps()
    }

    triggers {
        // ci-master is called by CD; do not independently poll it as well.
        pollSCM(env.JOB_NAME == 'yamu/ci' ? 'H/5 * * * *' : '')
    }

    environment {
        CI = 'true'
        TZ = 'UTC'
        NODE_OPTIONS = '--max-old-space-size=1024'
        VITE_API_BASE_URL = '/api'
    }

    stages {
        stage('Checkout') {
            steps {
                // Remove old results and generated files before checking out this revision.
                deleteDir()
                script {
                    def revision = checkout scm
                    env.GIT_COMMIT = revision.GIT_COMMIT
                }
                sh 'git rev-parse HEAD > ci-revision.txt'
                script {
                    if (fileExists('infra/gitlab/status.groovy')) {
                        def statusReporter = load 'infra/gitlab/status.groovy'
                        statusReporter.sendDevelop('RUNNING')
                    }
                }
            }
        }
        stage('Build environment') {
            steps {
                sh 'sh infra/ci/check-environment.sh'
                sh 'python3 infra/ci/test_check_test_results.py'
                sh 'python3 -m unittest discover -s infra/ci -p "test_notify_mattermost.py"'
                sh 'python3 -m unittest discover -s infra/cd -p "test_*.py"'
            }
        }
        stage('BE tests') {
            steps {
                dir('be') {
                    sh 'sh ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m" clean test'
                }
                sh 'python3 infra/ci/check-test-results.py be/build/test-results/test'
            }
            post {
                always {
                    junit testResults: 'be/build/test-results/test/TEST-*.xml', allowEmptyResults: false
                }
            }
        }
        stage('BE JAR') {
            steps {
                dir('be') {
                    sh 'sh ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m" bootJar'
                }
            }
        }
        stage('POS lint and build') {
            steps {
                dir('fe/pos') {
                    sh 'npm ci --no-audit --no-fund'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }
        stage('Mobile typecheck') {
            steps {
                dir('fe/mobile') {
                    sh 'npm ci --no-audit --no-fund'
                    sh './node_modules/.bin/tsc --noEmit'
                }
            }
        }
        stage('Archive') {
            steps {
                sh 'python3 infra/cd/manifest.py'
                archiveArtifacts artifacts: 'ci-revision.txt,ci-manifest.json,be/build/libs/*.jar,fe/pos/dist/**', fingerprint: true
            }
        }
    }

    post {
        always {
            script {
                if (fileExists('infra/gitlab/status.groovy')) {
                    def statusReporter = load 'infra/gitlab/status.groovy'
                    statusReporter.sendDevelop(currentBuild.currentResult)
                }
                if (env.JOB_NAME == 'yamu/ci' && fileExists('infra/ci/mattermost.groovy')) {
                    def notifier = load 'infra/ci/mattermost.groovy'
                    notifier.send('CI', currentBuild.currentResult)
                }
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
