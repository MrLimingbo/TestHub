{{- define "testhub.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := include "testhub.name" . }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "testhub.labels" -}}
helm.sh/chart: {{ include "testhub.chart" . }}
app.kubernetes.io/name: {{ include "testhub.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "testhub.selectorLabels" -}}
app.kubernetes.io/name: {{ include "testhub.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "testhub.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.apiServiceName" -}}
{{- printf "%s-api" (include "testhub.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.webServiceName" -}}
{{- printf "%s-web" (include "testhub.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.apiConfigName" -}}
{{- printf "%s-api-config" (include "testhub.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.webConfigName" -}}
{{- printf "%s-web-config" (include "testhub.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "testhub.image" -}}
{{- if .digest -}}
{{ .repository }}@{{ .digest }}
{{- else -}}
{{ .repository }}:{{ required "an immutable image tag is required" .tag }}
{{- end -}}
{{- end }}
