// Multibranch Pipeline. Every branch is tested; only main is pushed to GHCR and deployed.
//
// Jenkins setup:
//   - Global tool (JDK) named 'jdk21'; the agent must run Docker (Testcontainers, base image) and have ffmpeg on the
//     PATH (media tests). Jib builds the app image without Docker.
//   - Agent labelled 'macbook' runs the build.
//   - Credentials:
//       ghcr-credentials : Username/Password = GitHub user + classic PAT with write:packages and delete:packages
//   - Plugin: Workspace Cleanup (cleanWs).
//       deploy-ssh-key   : SSH private key for the deploy server
//   - Global property DEPLOY_HOST: user@host of the deploy server.
//   - Optional environment GHCR_OWNER: GitHub user/org that owns the image (defaults to the GHCR username).
//   - Optional environment DEPLOY_PORT: SSH port of the deploy server (defaults to 22).
pipeline {
    agent { label 'macbook' }

    tools {
        jdk 'jdk21'
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        IMAGE_NAME = 'workout-backend'
        // Keep in step with jib.from.image in build.gradle.kts.
        BASE_IMAGE = 'workout-backend:21-ffmpeg7'
        DEPLOY_DIR = '/opt/projects/workout'
    }

    stages {
        stage('Test') {
            steps {
                sh './gradlew clean test --no-daemon'
            }
            post {
                always {
                    junit 'build/test-results/test/*.xml'
                }
            }
        }

        // Jib builds the app on top of this image (build.gradle.kts). Rebuilt only when deploy/base-image changed since
        // the last successful build or the registry does not have it yet; --pull picks up Debian security updates.
        stage('Base image') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    sh '''
                        OWNER=$(echo "${GHCR_OWNER:-$GHCR_USER}" | tr '[:upper:]' '[:lower:]')
                        BASE="ghcr.io/$OWNER/$BASE_IMAGE"
                        # The agent runs as a system daemon without the user's keychain, so Docker's keychain credential
                        # helpers fail (Keychain Error -61). The macOS CLI picks such a helper whenever one is on the PATH,
                        # so run docker with the helpers hidden and a throwaway config; it is removed when the step ends.
                        DOCKER_BIN="$(command -v docker)"
                        docker() { PATH=/usr/bin:/bin:/usr/sbin:/sbin "$DOCKER_BIN" "$@"; }
                        export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
                        export DOCKER_CONFIG="$(mktemp -d)"
                        trap 'rm -rf "$DOCKER_CONFIG"' EXIT
                        ln -s "$HOME/.docker/cli-plugins" "$DOCKER_CONFIG/cli-plugins" # buildx
                        echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
                        if [ -n "$GIT_PREVIOUS_SUCCESSFUL_COMMIT" ] \
                            && git diff --quiet "$GIT_PREVIOUS_SUCCESSFUL_COMMIT" HEAD -- deploy/base-image \
                            && docker manifest inspect "$BASE" > /dev/null 2>&1; then
                            echo "base image unchanged: $BASE"
                            exit 0
                        fi
                        docker build --pull --platform linux/arm64 -t "$BASE" deploy/base-image
                        docker push "$BASE"
                    '''
                }
            }
        }

        stage('Publish image') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    script {
                        // GHCR repository names must be lowercase.
                        def owner = (env.GHCR_OWNER ?: env.GHCR_USER).toLowerCase()
                        env.IMAGE = "ghcr.io/${owner}/${env.IMAGE_NAME}"
                        // <semantic version from build.gradle.kts>-<git sha>
                        def version = sh(script: "./gradlew -q properties --property version --no-daemon | sed -n 's/^version: //p'",
                                returnStdout: true).trim()
                        def sha = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                        env.IMAGE_TAG = "${version}-${sha}"
                    }
                    // Jib reads GHCR_USER/GHCR_TOKEN from the environment (build.gradle.kts).
                    sh './gradlew jib --no-daemon -Djib.to.image="$IMAGE:$IMAGE_TAG" -Djib.to.tags=latest'
                }
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    // The server pulls with its own read-only GHCR login (see deploy setup), so the
                    // write token never leaves Jenkins.
                    sh '''
                        PORT="${DEPLOY_PORT:-22}"
                        SSH="ssh -p $PORT -o StrictHostKeyChecking=accept-new $DEPLOY_HOST"
                        COMPOSE="docker compose --env-file .env --env-file release.env"

                        $SSH "mkdir -p $DEPLOY_DIR"
                        scp -P "$PORT" -o StrictHostKeyChecking=accept-new deploy/compose.yaml "$DEPLOY_HOST:$DEPLOY_DIR/compose.yaml"
                        printf 'IMAGE=%s\\nIMAGE_TAG=%s\\n' "$IMAGE" "$IMAGE_TAG" | $SSH "cat > $DEPLOY_DIR/release.env"
                        $SSH "cd $DEPLOY_DIR && $COMPOSE pull app && $COMPOSE up -d"
                    '''
                }
            }
        }

        stage('Verify') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    // An unauthenticated call answering 401 means the app started and reached its DB.
                    sh '''
                        ssh -p "${DEPLOY_PORT:-22}" -o StrictHostKeyChecking=accept-new "$DEPLOY_HOST" '
                            for i in $(seq 1 30); do
                                code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/auth/session)
                                [ "$code" = "401" ] && exit 0
                                sleep 2
                            done
                            echo "app did not become ready (last status: $code)"
                            exit 1
                        '
                    '''
                }
            }
        }

        // Keep the 10 newest app images in GHCR, and only images in use on the server.
        stage('Prune images') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    script {
                        def owner = (env.GHCR_OWNER ?: env.GHCR_USER).toLowerCase()
                        // ponytail: user-owned package only; an org owner needs /orgs/ instead of /users/.
                        env.VERSIONS_API = "https://api.github.com/users/${owner}/packages/container/${env.IMAGE_NAME}/versions"
                        // ponytail: first 100 versions only; pruning on every build keeps the package far below that.
                        def json = sh(script: 'curl -fsS -H "Authorization: Bearer $GHCR_TOKEN" "$VERSIONS_API?per_page=100"',
                                returnStdout: true)
                        def stale = staleAppVersions(json, env.BASE_IMAGE.split(':')[1], 10, env.IMAGE_TAG)
                        if (stale == null) {
                            error "GHCR listing does not include ${env.IMAGE_TAG}; not pruning"
                        }
                        for (v in stale) {
                            echo "deleting ${env.IMAGE}:${v.tags.join(',')}"
                            withEnv(["VERSION_ID=${v.id}"]) {
                                sh 'curl -fsS -X DELETE -H "Authorization: Bearer $GHCR_TOKEN" "$VERSIONS_API/$VERSION_ID"'
                            }
                        }
                    }
                }
                sshagent(credentials: ['deploy-ssh-key']) {
                    // Removes every image no container uses; a rollback pulls an older one from GHCR.
                    sh 'ssh -p "${DEPLOY_PORT:-22}" -o StrictHostKeyChecking=accept-new "$DEPLOY_HOST" docker image prune -af'
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}

// The app's GHCR versions older than the `keep` newest, as [id, tags]. Newest means pushed last: Jib stamps every image
// with the epoch as its creation time. The base image and untagged versions (they may be parts of the base image's
// manifest list) are never returned. Returns null if the tag just deployed is not among the kept ones, since then the
// listing is not what we expect. Needs a one-time script approval for JsonSlurperClassic.
@NonCPS
def staleAppVersions(String json, String baseTag, int keep, String deployedTag) {
    def versions = new groovy.json.JsonSlurperClassic().parseText(json)
            .collect { [id: it.id, createdAt: it.created_at, tags: it.metadata.container.tags] }
            .findAll { it.tags && !(baseTag in it.tags) }
            .sort { a, b -> b.createdAt <=> a.createdAt }
    if (!versions.take(keep).any { deployedTag in it.tags }) {
        return null
    }
    versions.drop(keep).collect { [id: it.id, tags: it.tags] }
}
