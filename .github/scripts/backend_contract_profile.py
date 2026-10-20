#!/usr/bin/env python3
"""Fixed backend validation profiles and bounded evidence; no application or fixture code."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


FULL = 'full'
PILOT = 'f25-six-smoke-pilot'
NOTE_EXPIRY = 'note-expiry'
NOTE_BASE = 'a35ee31ba700093c97fa9c0864d6a8677e5fae78'
NOTE_APP_EXCEPTIONS = frozenset({
    'backend-app/README.md', 'backend-app/scripts/f4_ci.py',
    'backend-app/scripts/test_f4_ci_startup.py',
})
NOTE_HELPER_HASHES = {
    'backend-app/scripts/f4_ci.py': '68b4df20c29cd7d232d33ff1983c51a44c06302642e6d23d30474cd5e7babfa0',
    'backend-app/scripts/test_f4_ci_startup.py': 'a87a12457e2be5cf291c634345563a74b1d1b3cf658ab7f8dca4a5a03b2bca90',
}
NOTE_ALLOWED_PATHS = frozenset({
    '.github/workflows/backend-app-check.yml',
    '.github/scripts/backend_contract_profile.py',
    '.github/scripts/test_backend_contract_profile.py',
    '.github/scripts/test_backend_note_expiry_profile.py',
    '.github/scripts/test_backend_app_ci_policy.py',
    '.github/scripts/backend_postmerge_proof.py',
    '.github/scripts/test_backend_postmerge_proof.py',
    'backend-app/README.md', 'backend-app/boot/build.gradle',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F29LegacyNoteSendOracle.java',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlNoteSendCompatibilityTest.java',
    'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java',
    'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/NoteExpiryConfiguration.java',
    'backend-app/boot/src/test/java/com/pawbridge/backend/NoteExpiryConfigurationTest.java',
    'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/application/NoteExpiry.java',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcNoteExpiry.java',
    'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcNoteExpiryTest.java',
    'backend-app/scripts/prepare_f29_legacy_oracle.py',
}) | NOTE_APP_EXCEPTIONS
NOTE_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlNoteSendCompatibilityTest'
NOTE_METHODS = (
    'givenOwnedExpiryAndFortyEightHourBoundaries__whenBothExpireTwice__thenKeepForeignBaselineAndApplyFkThenOrphanCleanupEvenWhenFirstDeleteIsZero',
    'givenThreeHundredAndOneOwnedCandidatesPerTable__whenOriginalAndCandidateExpireOneBatch__thenCharacterizeOriginalAndRequireCandidateOldestThreeHundred',
    'givenOneLockedOwnedNoteAndOrphanRequest__whenBothExpire__thenSkipThoseRowsAndDeleteTheUnlockedOwnedCandidates',
    'givenOwnedRequestDeletePermissionFailure__whenCandidateHasAlreadyDeletedTheExpiredNote__thenRollbackBothSqlAndRestoreOnlyOwnedPrivileges',
    'givenAnOwnedDeferredDeleteConstraint__whenBothCommitExpiry__thenRejectFromThatConstraintAndRollbackAllEighteenTableChanges',
    'givenAnActiveExpiryWaitingForOwnedMailboxFk__whenActualBootSchedulerContextCloses__thenReturnTheTransactionBeforeWriterPoolDestructionAndNeverReschedule',
)
MEMBER_MANAGEMENT = 'member-management'
MEMBER_BASE = 'a35ee31ba700093c97fa9c0864d6a8677e5fae78'
MEMBER_APP_HASHES = {
    'backend-app/boot/build.gradle': '6c84c282ef7bb1788caa0d0fbb540625e7b5473809a4aa600e0cf7fe2c60a42f',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F29LegacyNoteSendOracle.java': '984f911d5747ae559c6c96dbcd9f305e1214b3d224d8898e430b264934f01d49',
    'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlNoteSendCompatibilityTest.java': 'bb68ebfcf12870e6e4fa43329da62dab892d3296b73ccb3d15cf29599dca4eee',
    'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '37a2d2308e6424e9436a48e44031ffb3668a5d9881180f60a74f3a7fc3a74beb',
    'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/MemberCleanupConfiguration.java': 'c5bad0f8c13275737697e17787dcfb2f5af2b38a2913bcf6468040cfa122146f',
    'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': '7509b9a8e0134c367062c58c8416e63aa4a086720b53c9da794b69e9a2b256ba',
    'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/UserProfileConfiguration.java': '60a03f902905e41c6b12cac5c834c3b77b4601a36c783555c9d604c60f3ebb9c',
    'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDeletionHttpTest.java': 'e7efffe8ff61d5d3d7118ba10fa60cdec20b5be309e879f19c4eb92a7e054232',
    'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDetailHttpTest.java': 'baf3d7076569c9d4c86339e5fc4df9719c4cedfd2bbe29d7945e1b3e025bbb24',
    'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '2284af2749683487968639288bc36b96e24b23ff05c8df3a0edf19e79b1b588b',
    'backend-app/boot/src/test/java/com/pawbridge/backend/TotalUserCountHttpTest.java': '596f7c15e83bd2fad217047a0a6ad76c9e35586b5ad78d6843b1405091ef5a6b',
    'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/MemberCleanupConfigurationTest.java': '610000331b88b9a61a580cdca57c06cd4c96b089a15093ac43ae6687e4518424',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/api/ContactMemberCleanup.java': '27bad8dfc23c4047dc39c6e3de1dde83ac362bbbb827832884f992180e6ee9f5',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/application/CleanupContactMember.java': 'b8c10b95c8a73eaa40803b7e30ef8ffa6a317a9a9b300d74ff1d55719704392d',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/application/ContactMemberCleanupRejectedException.java': 'a9cf882ba055060a1e4e0f94f034a938fab71693ac44f3ee091ab70209f3efca',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/application/ContactMemberCleanupStore.java': '4745a6ebbd83dd723696da55ce458314ffc63da2334a3a45b7a31720b0f27a68',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcContactMemberCleanupStore.java': '48601951cd9df83e0de787634b01515b2ad1b5164e1d3b05de1d27c4c78c08e4',
    'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/note/web/PrivateNoteStreamRegistry.java': '1ab6f24d629ed4a46e1aadb0f3d3ee83c1de8e5f016b7b409b4720a358505dee',
    'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/note/application/CleanupContactMemberTest.java': 'da453aa273ae49540af4eace73e6dd75703460a5979718c2fa43b9dbd1d94ad4',
    'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/note/infrastructure/JdbcContactMemberCleanupStoreTest.java': '44a14f3e8269c498aa7d4f89cfdb01bd0ff2e1d1b20ca3b17968fee52bccb0c7',
    'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/note/web/PrivateNoteStreamRegistryTest.java': '9f207dfaf84d8934cf61e392723832a7a24a2e222d0a8b82bad5825903cc725d',
    'backend-app/scripts/f29_ci.py': 'b116f5564138a7c50e9c3edf1d95d561f82b404c61a6be8d01e667382fa505e0',
    'backend-app/scripts/f29_stream_probe.py': '06d647659913525a345854bd32f915b7c529c7478df5d2ff456a6629da2fb2ef',
    'backend-app/scripts/f4_ci.py': '68b4df20c29cd7d232d33ff1983c51a44c06302642e6d23d30474cd5e7babfa0',
    'backend-app/scripts/prepare_f29_legacy_oracle.py': 'ce112ef5880cf5e5657b61a47b6cbf20f5b25c83000c176d7e908e54087aeb30',
    'backend-app/scripts/test_f29_ci_guards.py': '147e48b298c0d83f2426f87b574875cc542c96b6ce099967a35b215070dff300',
    'backend-app/scripts/test_f29_ci_runtime.py': '780c0582f713cabecb89bd581534e8df75c2684da1b5bd55d09d8bbe94a5177c',
    'backend-app/scripts/test_f4_ci_startup.py': 'a87a12457e2be5cf291c634345563a74b1d1b3cf658ab7f8dca4a5a03b2bca90',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/deletion/application/MemberCleanupUnavailableException.java': 'c076d9c04ac492d87dc220eb5fb222f7691d5124e8b0f348ed0b37e017c66928',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/deletion/application/UserDeletion.java': 'a713022325066d13dd88af5be0c217a8fa306e755fff1d02f2f669ebd73f26b4',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/deletion/infrastructure/JdbcUserDeletion.java': 'd5134f0fb87c8ac00eeff657340e0f56e9bf50b2067b75963d1b2b9a0b7767e3',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/deletion/web/AdminUserDeletionController.java': '5cf4c95c1d6313745facada14180a7943c7f549fd4b1be8bd3b82b628e497c4a',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/deletion/web/AdminUserDeletionExceptionHandler.java': '647b96d518cf4de5fffe86bc624c94de18c1e328b4379cc8f6bcf3a1904086a5',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/profile/web/AdminUserDetailController.java': 'fabf86b31c4630d28a0f5b807adc4f6b1a2dba289e5d8ec662da6005edb2ef66',
    'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/profile/web/UserProfileExceptionHandler.java': 'bc8d434943fdc2e3432ffebe1d4d01dc1a7984a3ab4b3f8f058f225e3d57c701',
    'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/deletion/infrastructure/JdbcUserDeletionTest.java': '89760971432b227a447df052a7322be4ebf1beb81630c03b14b4b9354d267642',
    'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/deletion/web/AdminUserDeletionControllerTest.java': '198b612092f83b2ab092a19909d494883f235d0cc685b09e4b7cd7f9dc66fe74',
}
MEMBER_APP_EXCEPTIONS = frozenset(MEMBER_APP_HASHES) | {'backend-app/README.md'}
MEMBER_ALLOWED_PATHS = NOTE_ALLOWED_PATHS | frozenset(MEMBER_APP_HASHES) | {
    '.github/scripts/test_backend_member_management_profile.py',
}
MEMBER_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'pg_note_expiry',
                             'fixture_member_cleanup', 'pg_member_cleanup')

SIGNUP_STATISTICS = 'signup-statistics'
SIGNUP_BASE = 'b59ddfbda475959bd9465e8dde709ad1cd2ca6c7'
SIGNUP_APP_HASHES = {'backend-app/boot/build.gradle': '770ef8ea7e734151d3e9140ed9751384fcba1fe0ea468ae202556e4ab22ec137',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F19LegacyTotalUserCountOracle.java': '22e0f852e8437667789e7d585d6f3412b1a10b1dd993cb9a2bf5ca8d13e6546a',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlTotalUserCountCompatibilityTest.java': '698de2e2062363bea9a09fc98d56a05a8c81ecd3e9a673c2e585d40727c70e76',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '5a9ba0a71baed672b6cc10fa0c8f6b0699f2669f7d51624109a833eb277742be',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': 'df687fe8c7dfde0756e64e94e662795ec6ef963ca238b9ff20e4d76752c050c4',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/SignupStatisticsReadConfiguration.java': '5d026112ae6e35fddc5ad1d73ceecefeafcdbd5fa960cd8ef95df386abd2f902',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': 'ae51c29980f457a4cc2caf308fd992718ad3c596587547c935997eb3a318308a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/SignupStatisticsHttpTest.java': 'af58979f7bb8c295def751e309622211f5039cec5ab92f216e941df64d5988b8',
 'backend-app/scripts/f19_ci.py': '4f5adad5f9780828694b30c36ce28939a10499876747c5c203bc0a5db26c6cf0',
 'backend-app/scripts/prepare_f19_legacy_oracle.py': '5f4cd169b38e878a740fe66ff0f9a122470f6751c2533c3373763a21c102b938',
 'backend-app/scripts/test_f19_ci_guards.py': '0d7962ac3f3a62f555d222557188673056cef9d300fa0d77b293f334121a96b5',
 'backend-app/user/build.gradle': 'ee4e5b8a7fe9692f32bd8ec3c01edfa743d438255b0a379ae05f8e17af082cb1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/statistics/application/SignupStatisticsQuery.java': '4070aef3286cdcf371c240a5378e85d8a0165896ec66c4a45ccf5a56f1757cd2',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/statistics/infrastructure/JdbcSignupStatisticsQuery.java': 'fed5c6173625763a8080f0605a87cb20f4331f00ba2f4f6b96ad66c9a42e6d8e',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/statistics/web/SignupStatisticsController.java': 'de5da00281eb44bfd72e9ae9312a105fef308632b984e2cdf8a8124c68b75ea4',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/statistics/web/TotalUserCountExceptionHandler.java': '758540248a0d1cc8175fbbaa6fac7731606393a9b16deee40214eba64eed68df',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/statistics/infrastructure/JdbcSignupStatisticsQueryTest.java': 'd0b3c2904ef9f3344d8d3c1e85646cc9b156ee18266fc72942dbdfa0621ae803',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/statistics/web/TotalUserCountExceptionHandlerTest.java': '7a521bfdf59e6bc2a87562a0b09b4356f66e3016c37afa2a3696f8106ca4e382'}
SIGNUP_APP_EXCEPTIONS = frozenset(SIGNUP_APP_HASHES) | {'backend-app/README.md'}
SIGNUP_ALLOWED_PATHS = MEMBER_ALLOWED_PATHS | frozenset(SIGNUP_APP_HASHES) | {
    '.github/scripts/test_backend_signup_statistics_profile.py',
}
SIGNUP_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'pg_signup_statistics')
SIGNUP_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlTotalUserCountCompatibilityTest'
SIGNUP_METHODS = (
    'givenUtcKstMicrosecondBoundariesNullAndAGap__whenDailyQueriesRunInEitherZone__thenMatchOriginalJpqlWithoutFillingOrReordering',
    'givenACommitAfterTheFirstPeriodQuery__whenFourQueriesShareReadCommitted__thenMatchOriginalRangesZeroFillAndLaterVisibility',
    'givenCreatedAtSelectRevoked__whenDailyReadFailsThenRecovers__thenReturnConnectionAndKeepTheOwnedBaseline',
)

REPOSITORY = 'pawbridge/pawbridge-backend-k8s'
PILOT_REF = 'ci/backend-contract-regression-scope'
PILOT_BASE = '0c1579a8ddb93cdea376be1ea57ac3b5c98bddf3'
PILOT_PATHS = frozenset({
    '.github/workflows/backend-app-check.yml',
    '.github/scripts/backend_contract_profile.py',
    '.github/scripts/test_backend_contract_profile.py',
    '.github/scripts/test_backend_app_ci_policy.py',
    'backend-app/README.md',
})
ALL_SMOKES = tuple('jar_f' + str(number) for number in range(3, 30)) + ('jar_f31',)
PILOT_SKIPS = tuple('jar_f' + str(number) for number in (16, 17, 19, 21, 22, 23))
IDENTITY_KEYS = ('event_name', 'repository', 'head_repository', 'head_ref',
                 'base_sha', 'head_sha', 'source_sha', 'tree_sha', 'run_id', 'run_attempt')


class ProfileBlocked(ValueError):
    """Only fixed non-sensitive messages may cross the CLI boundary."""


def _paths(paths):
    if (not isinstance(paths, (list, tuple))
            or any(not isinstance(path, str) or not path for path in paths)
            or len(set(paths)) != len(paths)):
        raise ProfileBlocked('Changed paths are invalid or duplicated.')
    return sorted(paths)


def verify_note_expiry_inputs(git_reader=None):
    """Pin every application/build/test input to the already inspected F30 source.

    Only README and the separately reviewed F4 shape bootstrap may differ. CI
    policy itself remains a reviewed source change, never proof of its own safety.
    """
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        raw = git('diff', '--name-only', '--no-renames', '-z', NOTE_BASE, 'HEAD', '--', 'backend-app')
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(NOTE_APP_EXCEPTIONS):
            return False
        return all(hashlib.sha256(git('show', 'HEAD:' + path)).hexdigest() == digest
                   for path, digest in NOTE_HELPER_HASHES.items())
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_member_management_inputs(git_reader=None):
    """Accept only the reviewed member app and F4 shape bytes on the fixed F30 base."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        raw = git('diff', '--name-only', '--no-renames', '-z', MEMBER_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(MEMBER_APP_EXCEPTIONS):
            return False
        for path, digest in MEMBER_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_signup_statistics_inputs(git_reader=None):
    """Read pinned Git objects; unreviewed changes or incomplete source pins stay full."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if len(SIGNUP_APP_HASHES) != 18:
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', SIGNUP_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(SIGNUP_APP_EXCEPTIONS):
            return False
        for path, digest in SIGNUP_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_signup_statistics_report(utc_directory, seoul_directory):
    """Require the three tagged UTC cases and only the same boundary case in Seoul."""
    for directory, methods in ((utc_directory, SIGNUP_METHODS), (seoul_directory, SIGNUP_METHODS[:1])):
        try:
            directory = Path(directory)
            reports = list(directory.glob('TEST-*.xml'))
            if directory.is_symlink() or len(reports) != 1 or reports[0].is_symlink():
                raise ProfileBlocked('The signup PostgreSQL report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '').removesuffix('()') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != SIGNUP_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(cases) or len(cases) != len(methods)
                    or len(set(names)) != len(methods) or set(names) != set(methods)
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != SIGNUP_REPORT_CLASS or
                           case.attrib.get('status', 'run') not in ('run', 'passed') for case in cases)):
                raise ProfileBlocked('The signup PostgreSQL cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The signup PostgreSQL report is invalid.') from None


def verify_note_expiry_report(directory):
    try:
        directory = Path(directory)
        reports = list(directory.glob('TEST-*.xml'))
        if directory.is_symlink() or len(reports) != 1 or reports[0].is_symlink():
            raise ProfileBlocked('The scoped PostgreSQL report is missing or redirected.')
        suite = ET.parse(reports[0]).getroot()
        cases = list(suite.iter('testcase'))
        names = [case.attrib.get('name', '').removesuffix('()') for case in cases]
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != NOTE_REPORT_CLASS
                or int(suite.attrib.get('tests', -1)) != len(cases) or len(cases) != 6
                or len(set(names)) != 6 or set(names) != set(NOTE_METHODS)
                or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                       for key in ('failures', 'errors', 'skipped', 'disabled'))
                or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                or any(case.attrib.get('classname') != NOTE_REPORT_CLASS or
                       case.attrib.get('status', 'run') not in ('run', 'passed') for case in cases)):
            raise ProfileBlocked('The scoped PostgreSQL cases did not all execute successfully.')
    except (OSError, ValueError, TypeError, ET.ParseError):
        raise ProfileBlocked('The scoped PostgreSQL report is invalid.') from None


def select_profiles(context, paths):
    """Unknown/fork/new-base changes stay full; explicit ineligible pilot is refused."""
    paths = _paths(paths)
    if not isinstance(context, dict):
        raise ProfileBlocked('Profile context is invalid.')
    requested = context.get('requested_profile')
    if requested not in (None, '', FULL, PILOT, NOTE_EXPIRY, MEMBER_MANAGEMENT, SIGNUP_STATISTICS):
        raise ProfileBlocked('Requested profile is unknown.')
    if requested == FULL:
        return [FULL]
    scoped = (context.get('event_name') in ('pull_request', 'push')
              and context.get('repository') == REPOSITORY
              and context.get('head_repository') == REPOSITORY
              and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
              and context.get('note_expiry_scope_verified') is True
              and bool(paths) and set(paths).issubset(NOTE_ALLOWED_PATHS))
    if requested == NOTE_EXPIRY and not scoped:
        raise ProfileBlocked('The requested note expiry scope differs from its reviewed inputs.')
    if scoped and requested in (None, '', NOTE_EXPIRY):
        return [NOTE_EXPIRY]
    member_scoped = (context.get('event_name') in ('pull_request', 'push')
                     and context.get('repository') == REPOSITORY
                     and context.get('head_repository') == REPOSITORY
                     and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                     and context.get('member_management_scope_verified') is True
                     and bool(paths) and set(paths).issubset(MEMBER_ALLOWED_PATHS))
    if requested == MEMBER_MANAGEMENT and not member_scoped:
        raise ProfileBlocked('The requested member scope differs from its reviewed inputs.')
    if member_scoped and requested in (None, '', MEMBER_MANAGEMENT):
        return [MEMBER_MANAGEMENT]
    signup_scoped = (context.get('event_name') in ('pull_request', 'push')
                     and context.get('repository') == REPOSITORY
                     and context.get('head_repository') == REPOSITORY
                     and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                     and context.get('signup_statistics_scope_verified') is True
                     and bool(paths) and set(paths).issubset(SIGNUP_ALLOWED_PATHS))
    if requested == SIGNUP_STATISTICS and not signup_scoped:
        raise ProfileBlocked('The requested signup scope differs from its reviewed inputs.')
    if signup_scoped and requested in (None, '', SIGNUP_STATISTICS):
        return [SIGNUP_STATISTICS]
    eligible = (context.get('event_name') == 'pull_request'
                and context.get('repository') == REPOSITORY
                and context.get('head_repository') == REPOSITORY
                and context.get('head_ref') == PILOT_REF
                and context.get('base_sha') == PILOT_BASE
                and bool(paths) and set(paths).issubset(PILOT_PATHS))
    if requested == PILOT and not eligible:
        raise ProfileBlocked('The requested pilot does not match its fixed scope.')
    if eligible and requested != FULL:
        return [FULL, PILOT]
    return [FULL]


def expected_smokes(profile):
    if profile == SIGNUP_STATISTICS:
        return ()
    if profile == MEMBER_MANAGEMENT:
        return ('jar_f31',)
    if profile == NOTE_EXPIRY:
        return ()
    if profile == FULL:
        return ALL_SMOKES
    if profile == PILOT:
        return tuple(step for step in ALL_SMOKES if step not in PILOT_SKIPS)
    raise ProfileBlocked('Execution profile is unknown.')


def _identity(context):
    try:
        identity = {key: context[key] for key in IDENTITY_KEYS}
    except (KeyError, TypeError):
        raise ProfileBlocked('Candidate identity is incomplete.') from None
    if any(not isinstance(value, str) or not value for value in identity.values()):
        raise ProfileBlocked('Candidate identity contains invalid values.')
    if identity['event_name'] not in ('pull_request', 'push'):
        raise ProfileBlocked('Candidate event is unsupported.')
    for key in ('base_sha', 'head_sha', 'source_sha', 'tree_sha'):
        value = identity[key]
        if (not re.fullmatch(r'[0-9a-f]{40}', value)
                or (value == '0' * 40 and not (key == 'base_sha' and identity['event_name'] == 'push'))):
            raise ProfileBlocked('Candidate commit or tree identity is invalid.')
    for key in ('repository', 'head_repository'):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', identity[key]):
            raise ProfileBlocked('Candidate repository identity is invalid.')
    if (any(ord(char) < 32 for char in identity['head_ref'])
            or any(not re.fullmatch(r'[1-9][0-9]*', identity[key]) for key in ('run_id', 'run_attempt'))):
        raise ProfileBlocked('Candidate ref or run identity is invalid.')
    return identity


def _encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def make_plan(context, paths, profile, jar_sha256):
    required = expected_smokes(profile)
    identity = _identity(context)
    paths = _paths(paths)
    selection = dict(context, requested_profile=profile)
    if profile not in select_profiles(selection, paths):
        raise ProfileBlocked('Execution profile is not eligible.')
    if not isinstance(jar_sha256, str) or not re.fullmatch(r'[0-9a-f]{64}', jar_sha256):
        raise ProfileBlocked('The executable JAR hash is invalid.')
    result = {
        'schema_version': 1, 'status': 'planned', 'profile': profile,
        'identity': identity, 'jar_sha256': jar_sha256,
        'changed_paths_sha256': hashlib.sha256(_encoded(paths)).hexdigest(),
        'selection_reason': ('fixed-signup-inputs-and-scoped-postgresql' if profile == SIGNUP_STATISTICS else
                             'fixed-member-inputs-and-scoped-postgresql' if profile == MEMBER_MANAGEMENT else
                             'fixed-f30-inputs-and-scoped-postgresql' if profile == NOTE_EXPIRY else
                             'fixed-f25-ci-only-pilot' if profile == PILOT else 'full-reference-or-default'),
        'required_smokes': list(required),
        'skipped_smokes': [step for step in ALL_SMOKES if step not in required],
    }
    if profile == NOTE_EXPIRY:
        result['required_contracts'] = ['fixture_f4_shape', 'pg_note_expiry']
    elif profile == MEMBER_MANAGEMENT:
        result['required_contracts'] = list(MEMBER_REQUIRED_CONTRACTS)
    elif profile == SIGNUP_STATISTICS:
        result['required_contracts'] = list(SIGNUP_REQUIRED_CONTRACTS)
    return result


def audit_execution(plan, context, paths, profile, jar_sha256, steps):
    """Validate both raw outcomes and conclusions; never persist arbitrary step outputs."""
    expected = make_plan(context, paths, profile, jar_sha256)
    if not isinstance(plan, dict) or _encoded(plan) != _encoded(expected):
        raise ProfileBlocked('The execution plan differs from the current candidate or profile.')
    if (not isinstance(steps, dict) or any(not isinstance(key, str) for key in steps)
            or {key for key in steps if key.startswith('jar_f')} != set(ALL_SMOKES)):
        raise ProfileBlocked('Packaged step identities are missing, unknown or duplicated.')
    results = {}
    required = set(expected['required_smokes'])
    contracts = tuple(expected.get('required_contracts', ()))
    for key in ALL_SMOKES + contracts + ('owned_cleanup',):
        step = steps.get(key)
        wanted = 'success' if key == 'owned_cleanup' or key in required or key in contracts else 'skipped'
        if (not isinstance(step, dict) or step.get('outcome') != wanted
                or step.get('conclusion') != wanted):
            raise ProfileBlocked('A required execution, planned skip or owned cleanup differs.')
        results[key] = {'outcome': wanted, 'conclusion': wanted}
    result = {
        'schema_version': 1, 'status': 'passed', 'profile': profile,
        'identity': expected['identity'], 'jar_sha256': jar_sha256,
        'changed_paths_sha256': expected['changed_paths_sha256'],
        'plan_sha256': hashlib.sha256(_encoded(expected)).hexdigest(),
        'required_smokes': expected['required_smokes'],
        'executed_smokes': expected['required_smokes'],
        'skipped_smokes': expected['skipped_smokes'], 'step_results': results,
    }
    if contracts:
        result['required_contracts'] = list(contracts)
    return result


def load_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ProfileBlocked('JSON evidence repeats a key.')
            result[key] = value
        return result

    def invalid_constant(_value):
        raise ProfileBlocked('JSON evidence contains a non-finite value.')

    try:
        return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (TypeError, ValueError):
        raise ProfileBlocked('JSON evidence is malformed or duplicated.') from None


def runtime_context():
    """Reuse the existing event/source/diff policy; do not duplicate its Git checks."""
    env = os.environ
    try:
        import ci_branch_policy as policy

        payload = load_json(Path(env['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        event = policy.validate_event(env)
        paths = policy.changed_paths(env, event)
        repository = env['GITHUB_REPOSITORY']
        source = env['GITHUB_SHA']
        if event == 'pull_request':
            pull = payload['pull_request']
            head = pull['head']
            base = pull['base']['sha']
            if base != env.get('PULL_REQUEST_BASE_SHA'):
                raise ProfileBlocked('Event base differs from the verified change comparison.')
            head_repo, head_ref, head_sha = head['repo']['full_name'], head['ref'], head['sha']
        else:
            base = payload['before']
            if base != env.get('PUSH_BEFORE_SHA'):
                raise ProfileBlocked('Push base differs from the verified change comparison.')
            head_repo, head_ref, head_sha = repository, env['GITHUB_REF'].removeprefix('refs/heads/'), source
        context = dict(event_name=event, repository=repository, head_repository=head_repo,
                       head_ref=head_ref, base_sha=base, head_sha=head_sha, source_sha=source,
                       tree_sha=policy.git('rev-parse', 'HEAD^{tree}').decode('ascii').strip(),
                       run_id=env['GITHUB_RUN_ID'], run_attempt=env['GITHUB_RUN_ATTEMPT'])
        identity = _identity(context)
        identity['note_expiry_scope_verified'] = verify_note_expiry_inputs()
        identity['member_management_scope_verified'] = verify_member_management_inputs()
        identity['signup_statistics_scope_verified'] = verify_signup_statistics_inputs()
        return identity, _paths(paths)
    except (ImportError, KeyError, TypeError, AttributeError, OSError, UnicodeError, ValueError):
        raise ProfileBlocked('The verified runtime context could not be constructed.') from None


def _jar_hash(path):
    if path.is_symlink() or not path.is_file():
        raise ProfileBlocked('The executable JAR is missing or redirected.')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _write_evidence(path, expected_name, value):
    if path.name != expected_name or path.is_symlink():
        raise ProfileBlocked('The evidence destination is invalid or redirected.')
    # Exclusive creation preserves the first result and rejects duplicate evidence.
    with path.open('x', encoding='utf-8') as output:
        output.write(_encoded(value).decode('ascii') + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('plan', 'audit'), required=True)
    parser.add_argument('--profile', choices=(FULL, PILOT, NOTE_EXPIRY, MEMBER_MANAGEMENT, SIGNUP_STATISTICS), required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--execution', type=Path)
    args = parser.parse_args(argv)
    context = None
    jar_hash = None
    try:
        context, paths = runtime_context()
        jar_hash = _jar_hash(args.jar)
        if args.mode == 'plan':
            _write_evidence(args.plan, 'contract-plan.txt', make_plan(context, paths, args.profile, jar_hash))
        else:
            if args.execution is None or args.execution.parent.resolve() != args.plan.parent.resolve():
                raise ProfileBlocked('Execution evidence must share the plan directory.')
            if args.plan.is_symlink() or not args.plan.is_file():
                raise ProfileBlocked('The execution plan is missing or redirected.')
            plan = load_json(args.plan.read_text(encoding='utf-8'))
            steps = load_json(os.environ.get('CONTRACT_STEPS_JSON', ''))
            result = audit_execution(plan, context, paths, args.profile, jar_hash, steps)
            _write_evidence(args.execution, 'contract-execution.txt', result)
    except (ProfileBlocked, OSError, UnicodeError):
        if args.mode == 'audit' and args.execution is not None:
            failed = {'schema_version': 1, 'status': 'failed', 'profile': args.profile}
            if context is not None:
                failed['identity'] = context
            if jar_hash is not None:
                failed['jar_sha256'] = jar_hash
            try:
                _write_evidence(args.execution, 'contract-execution.txt', failed)
            except (ProfileBlocked, OSError):
                pass  # Never replace earlier evidence or hide the original failed exit status.
        print('Contract profile verification failed.', file=sys.stderr)
        return 1
    print('Contract profile ' + args.mode + ' verified.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
