package execution

import (
	"errors"
	"path"
	"strings"
	"unicode/utf8"
)

// ErrCommandOutputLimit stops a development process whose retained copy exceeds its budget.
var ErrCommandOutputLimit = errors.New("command output exceeds workspace budget")

// ErrCommandAuthorityEnded fences a running command after its original grant ends.
var ErrCommandAuthorityEnded = errors.New("command write authority ended")

// ValidateCommand checks command syntax and copy identity, not user authorization.
func ValidateCommand(input Input) error {
	command := input.Command
	if input.Purpose != "COMMAND" || command == nil || input.TaskID == "" || input.Target != "bash" || input.Suite != "" || len(input.Tests) != 0 {
		return errors.New("development command cannot carry an authority test contract")
	}
	if input.Subpath != input.WorkspaceID+"/command-"+input.ExecutionID+"/tree" {
		return errors.New("command must identify exactly one mutable execution copy")
	}
	if strings.TrimSpace(command.Text) == "" || len(command.Text) > 4096 || !utf8.ValidString(command.Text) || strings.ContainsRune(command.Text, 0) {
		return errors.New("invalid command text")
	}
	directory := command.Directory
	if directory == "" || len(directory) > 1024 || !utf8.ValidString(directory) || strings.ContainsAny(directory, "\\\x00\r\n") || path.IsAbs(directory) || path.Clean(directory) != directory || directory == ".." || strings.HasPrefix(directory, "../") {
		return errors.New("command directory must be a normalized project-relative path")
	}
	return nil
}

// The holding parent publishes the actual Bash exit code. The writable project
// lives in a dedicated retained copy, so killing the container on stop/timeout
// cannot erase source modifications before the workspace coordinator sees them.
const developmentCommand = `cd -- "/project/$CODECRAFT_DIRECTORY"
status=$?
if [ "$status" -eq 0 ]; then
  /bin/bash --noprofile --norc -c "$CODECRAFT_COMMAND"
  status=$?
fi
printf '%s' "$status" > /work/.result-status.tmp || exit 91
mv /work/.result-status.tmp /work/result-status || exit 92
while :; do sleep 1; done`
