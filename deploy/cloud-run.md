# Deploying to Google Cloud Run + Cloud SQL

Record of the commands used to deploy `chat-service`, in the order they were run. Secrets are never written here;
each one is typed into a prompt or read from a local file and stored in Secret Manager.

Commands are PowerShell (Windows). Every `gcloud` command passes `--project` explicitly, so the machine's default
gcloud project is never changed.

## Variables

```powershell
$PROJECT  = 'quarkustest-491617'        # existing project with billing enabled
$REGION   = 'asia-south1'               # Mumbai: close to SalesIQ's India data centre and the visitors
$SERVICE  = 'chat-service'              # Cloud Run service
$SQL      = 'chat-db'                   # Cloud SQL instance
$DB       = 'chat'                      # database name
$DB_USER  = 'chat'                      # database user
$REPO     = 'chat'                      # Artifact Registry repository
$VERSION  = 'v0.1.1'                    # git tag being deployed
$COMMIT   = 'bdf4b2a'                   # commit of that tag (git rev-parse --short v0.1.1)
$IMAGE    = "$REGION-docker.pkg.dev/$PROJECT/$REPO/$SERVICE"
```

## 1. Project

A dedicated project (`colourcoats-agent-5893`) was created first, but billing could not be linked to it (the billing
account's project limit was reached), so the existing test project `quarkustest-491617` is used. All resources
created below are named `chat-*` / `chat-service` so they can be removed precisely afterwards (see "Clean up").

```powershell
gcloud projects create colourcoats-agent-5893 --name="ColourCoats Agent"   # not used: billing could not be linked
gcloud billing projects describe $PROJECT --format="value(billingEnabled)" # True
```

## 2. Enable the APIs

Free; charges start only when resources are created.

```powershell
gcloud services enable sqladmin.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com `
  run.googleapis.com artifactregistry.googleapis.com iam.googleapis.com --project $PROJECT
```

## 3. Cloud SQL (PostgreSQL 17 with pgvector)

Smallest shared-core instance, one zone, no automatic backups (a short-lived demo). Billed per hour until deleted.

```powershell
gcloud sql instances create $SQL --project $PROJECT --region $REGION --database-version POSTGRES_17 `
  --edition ENTERPRISE --tier db-f1-micro --storage-type SSD --storage-size 10 --availability-type zonal `
  --no-backup --async
```

## 4. Image repository and the app's service account

The service account starts with no permissions: Cloud SQL client now, read access to its own secrets in step 6.

```powershell
gcloud artifacts repositories create $REPO --repository-format docker --location $REGION `
  --description "chat-service images" --project $PROJECT
gcloud iam service-accounts create $SERVICE --display-name "chat-service (Cloud Run)" --project $PROJECT
$SA = "$SERVICE@$PROJECT.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding $PROJECT --member "serviceAccount:$SA" --role roles/cloudsql.client --condition None
```

## 5. Build the image (Cloud Build)

Run from the repository root with the release tag checked out (`git diff $VERSION -- chat-service` is empty).
The upload honours `.gitignore`, so `target/` and `logs/` are not sent.

```powershell
gcloud builds submit chat-service --project $PROJECT --region $REGION --tag "${IMAGE}:$VERSION" --suppress-logs
```

First attempt (`v0.1.0`) failed: `./mvnw: Permission denied`. Files uploaded from Windows carry no executable
bit, so the Dockerfile now runs `chmod +x mvnw` before using it (fixed in `v0.1.1`).

The fix was test-built from its branch before merging, then the same image was given the release tags (the merged
`chat-service` folder is identical to the tested commit, `git diff 823d44a v0.1.1 -- chat-service` is empty):

```powershell
gcloud builds submit chat-service --project $PROJECT --region $REGION --tag "${IMAGE}:test-823d44a" --suppress-logs
git tag -a v0.1.1 bdf4b2a -m "v0.1.1: Docker build makes the Maven wrapper executable (Cloud Build from Windows)"
git push origin v0.1.1
gcloud artifacts docker tags add "${IMAGE}:test-823d44a" "${IMAGE}:$VERSION"
gcloud artifacts docker tags add "${IMAGE}:test-823d44a" "${IMAGE}:$COMMIT"
```

## 6. Database, user and secrets

The database password is generated, set on the user and stored in Secret Manager without being printed.

```powershell
gcloud sql databases create $DB --instance $SQL --project $PROJECT
$bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pw = ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
gcloud sql users create $DB_USER --instance $SQL --project $PROJECT --password $pw
$f = New-TemporaryFile; [IO.File]::WriteAllText($f, $pw)
gcloud secrets create chat-db-password --data-file $f --replication-policy automatic --project $PROJECT
Remove-Item $f; Remove-Variable pw, bytes
```

The OpenAI key and the operator password are typed by the owner (hidden input, written without a trailing newline
to a temporary file that is deleted afterwards):

```powershell
function New-ChatSecret($name, $prompt) {
  $v = [Net.NetworkCredential]::new('', (Read-Host $prompt -AsSecureString)).Password
  $f = New-TemporaryFile; [IO.File]::WriteAllText($f, $v)
  gcloud secrets create $name --data-file $f --replication-policy automatic --project $PROJECT
  Remove-Item $f
}
New-ChatSecret 'chat-openai-api-key'     'OpenAI API key'
New-ChatSecret 'chat-operator-password'  'Operator password for the lead console'
```

The app's service account may read exactly these three secrets:

```powershell
foreach ($s in 'chat-openai-api-key','chat-db-password','chat-operator-password') {
  gcloud secrets add-iam-policy-binding $s --project $PROJECT --member "serviceAccount:$SA" --role roles/secretmanager.secretAccessor
}
```

**From the release with evals in the console:** the share of eval cases a prompt draft must pass before it can be
activated is a secret too (`EVAL_ACTIVATION_MIN_PASS_RATE`, `0.85` = 85%; `0` switches the gate off). Create it once,
let the app read it, and pass it on the next deploy:

```powershell
$f = New-TemporaryFile; [IO.File]::WriteAllText($f, '0.85')
gcloud secrets create chat-eval-min-pass-rate --data-file $f --replication-policy automatic --project $PROJECT
Remove-Item $f
gcloud secrets add-iam-policy-binding chat-eval-min-pass-rate --project $PROJECT --member "serviceAccount:$SA" --role roles/secretmanager.secretAccessor
# with the new image:
gcloud run services update $SERVICE --project $PROJECT --region $REGION --image "${IMAGE}:$VERSION" `
  --update-env-vars APP_COMMIT=$COMMIT --update-secrets "EVAL_ACTIVATION_MIN_PASS_RATE=chat-eval-min-pass-rate:latest"
```

To change it later, add a secret version (`gcloud secrets versions add chat-eval-min-pass-rate --data-file <file>`) and
redeploy the same image (`gcloud run services update $SERVICE ... --update-secrets ...`), which makes a new revision read it.

## 7. Deploy to Cloud Run

Public (SalesIQ and website visitors call it), one warm instance so the first message never waits for a cold start
(SalesIQ allows 5 s), database through the Cloud SQL connector, secrets as environment variables.

```powershell
$DBURL = "jdbc:postgresql:///$DB?cloudSqlInstance=${PROJECT}:${REGION}:$SQL&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
gcloud run deploy $SERVICE --project $PROJECT --region $REGION --image "${IMAGE}:$VERSION" --service-account $SA `
  --allow-unauthenticated --min-instances 1 --max-instances 1 --memory 1Gi --cpu 1 --cpu-boost `
  --set-env-vars "DB_URL=$DBURL,DB_USERNAME=$DB_USER,APP_COMMIT=$COMMIT,OPENAI_CHAT_MODEL=gpt-4.1-mini" `
  --set-secrets "OPENAI_API_KEY=chat-openai-api-key:latest,DB_PASSWORD=chat-db-password:latest,OPERATOR_PASSWORD=chat-operator-password:latest"
```

Result: revision `chat-service-00001-xmh`, URL https://chat-service-343129434945.asia-south1.run.app

Check:

```powershell
$URL = 'https://chat-service-343129434945.asia-south1.run.app'
curl.exe $URL/actuator/health   # {"status":"UP"}: running and connected to Cloud SQL (Flyway migrated it)
curl.exe $URL/actuator/info     # {"app":{...,"commit":"bdf4b2a"}}: the deployed release
```

To change only the chat model later (no rebuild): `gcloud run services update $SERVICE --project $PROJECT --region $REGION --update-env-vars OPENAI_CHAT_MODEL=<model>`.

## 8. Releasing a new version

The version in `chat-service/pom.xml` is the one `/actuator/info` reports, so it is set in its own PR first
(`chore/release-<version>`). After that PR is merged, the merge commit is tagged, built and deployed. Only the image
and `APP_COMMIT` change; secrets, database and the other settings stay as they are.

`v0.1.2` (first release with the pom version set; `v0.1.1` still reported `0.0.1-SNAPSHOT`):

```powershell
$VERSION = 'v0.1.2'
$COMMIT  = '5b9c8b2'                    # merge commit of PR #18

git switch main; git pull
git tag -a $VERSION $COMMIT -m "Release 0.1.2"
git push origin $VERSION
git diff $VERSION --stat -- chat-service    # empty: building exactly the tagged code

gcloud builds submit chat-service --project $PROJECT --region $REGION --tag "${IMAGE}:$VERSION" --suppress-logs
gcloud artifacts docker tags add "${IMAGE}:$VERSION" "${IMAGE}:$COMMIT"
gcloud run services update $SERVICE --project $PROJECT --region $REGION --image "${IMAGE}:$VERSION" `
  --update-env-vars APP_COMMIT=$COMMIT
```

Result: build `96a056ba` (1 min 53 s), revision `chat-service-00002-jqt` serving 100% of traffic.

```powershell
curl.exe $URL/actuator/health   # {"groups":["liveness","readiness"],"status":"UP"}
curl.exe $URL/actuator/info     # {"app":{"name":"chat-service","version":"0.1.2","commit":"5b9c8b2"}}
```

Rolling back means pointing the service at the previous image: `gcloud run services update $SERVICE --project $PROJECT --region $REGION --image "${IMAGE}:v0.1.1" --update-env-vars APP_COMMIT=bdf4b2a`.
