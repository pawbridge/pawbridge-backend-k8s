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

ADMIN_MEMBER_UPDATE = 'admin-member-update'
ADMIN_BASE = '25bc27575f728f2e0bf710fc867fb067f2465255'
ADMIN_APP_HASHES = {'backend-app/boot/build.gradle': '999bad00e323ad23ebea0663c76cdbeb7d3fa235d45869a3bb36313f7a30407f',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F13LegacyNicknameOracle.java': 'e5c368c21185f7431fc7f430010632ea4291deab7dce8a93ed58a8e2210f5f83',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlAdminUserUpdateCompatibilityTest.java': '318062f5ca360e1eb936a1a1eb60ee6bc34532691041a47f7fe40705aea79d72',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '84a6d718dc9b263029df6679b534494531ab340d3d8dd5cf0a30ac7d139df2bf',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AdminUserUpdateConfiguration.java': 'e82fa1b05be1bafad428e28bfdd026ab56c85a457f37fb152e89a9348f0d651c',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': '859dbd8468543cdb34a9782202559af20a108bf3b0c8745faac1ed758493976a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDeletionHttpTest.java': 'db91f530d0ba0b0f2476a80376ac5a2895a79e50b591213646bfb50d397921f5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDetailHttpTest.java': '5f18b88b647bde90b6c9a80ebace006b50796ba648c60abb594d9a8a5d04a720',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserUpdateHttpTest.java': '75cead442e63c18e19e70bb98a4aae521fbbf5af6cee252ec604e65eebe08d17',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': 'ec9c4f31b2c80fdadf478034c4d7eb54c485487ef0b7f91921303852f7c2131e',
 'backend-app/scripts/f29_ci.py': '3b37db2fa6d869aa6717320949d58a8f797613bdfacca1076d02226b10832e01',
 'backend-app/scripts/prepare_f13_legacy_oracle.py': 'ac87461ab2cfa68352fb354123ecc54ed72cd5de528c2af5e8b617345018bde9',
 'backend-app/scripts/test_f13_ci_guards.py': '9726f865ce057bc35a9c69d2530804caf479047e4d9fa88a639a3cf0e59a0520',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/application/AdminUserUpdate.java': '61b0496fd4ca1a16c67c0f3ac586c7da93d0290b0f6401fa5647b70707d75260',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/application/ShelterApprovalRequiredException.java': 'ec2364076accc202b1f17013869c443b8fed869d469a93a62af06e81f8faafd5',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/infrastructure/JdbcAdminUserUpdate.java': '293ff00530b02d9868c0be5efa43188e09820e87d7f13d692f3e71aba33c43d5',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/web/AdminUserUpdateController.java': '19e88eb6c09185bd0df2429effb119e624ef33b246a4c27c8912da54f7cb13b9',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/web/AdminUserUpdateExceptionHandler.java': 'b60e4ecd5b1b167c286fc31cb8ca13cb35ec2509d2ab2372f0637d7d10d7fef4',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/adminupdate/web/AdminUserUpdateRequest.java': '5ddca2b2059fa93287b1c8f40008edb06d51e9733d25cb780caf65dda766dc1d',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/adminupdate/infrastructure/JdbcAdminUserUpdateTest.java': '1a7beac0fa0b2e70665f7900a7054f3d08e665e397ef9877c15507b0e96ee06a'}
ADMIN_APP_EXCEPTIONS = frozenset(ADMIN_APP_HASHES) | {'backend-app/README.md'}
ADMIN_ALLOWED_PATHS = SIGNUP_ALLOWED_PATHS | frozenset(ADMIN_APP_HASHES) | {
    '.github/scripts/test_backend_admin_member_update_profile.py',
}
ADMIN_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'compile_admin_update', 'fixture_member_cleanup',
                            'fixture_admin_update', 'pg_admin_update')
ADMIN_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlAdminUserUpdateCompatibilityTest'
ADMIN_METHOD_CASES = (
    ('givenAnActualDirtyManagedUser__whenUpdatingAsAdmin__thenMatchTheOriginalMicrosecondAuditAndPreserveEveryOtherColumn', 3),
    ('givenNoDirtyProperty__whenTheOriginalStillSaves__thenNeitherImplementationUpdatesTheRowOrAuditTimestamp', 2),
    ('givenCompetingBusinessRejections__whenUpdating__thenPreserveMissingApprovalAndNicknamePrecedenceWithoutAnyMutation', 5),
    ('givenAnExactNewColumnPrivilegeRevoked__whenUpdatingThenRestoring__thenRollbackTheWholeRowAndRecover', 2),
    ('givenTheLookupHasLockedOneMember__whenAnIndependentConnectionAttemptsToChangeIt__thenObserveTheActualPgBlockUntilCommit', 2),
    ('givenTwoDistinctLockedMembersPassTheAvailableCheck__whenTheirUniqueNicknameUpdatesRace__thenOneCommitAndOneSql23505RemainServerFailuresWithoutPartialRoleChanges', 2),
)
ADMIN_REPORT_CASES = tuple(method + '[' + str(index) + ']'
                           for method, count in ADMIN_METHOD_CASES for index in range(1, count + 1))


VISIT_COLLECTION = 'visit-collection'
VISIT_BASE = 'd938003e464c1413b8c27071e3f07d85b4e25ed6'
VISIT_APP_HASHES = {'backend-app/boot/build.gradle': 'e06453da8a4f23d3b939c1abccb5b17829579353267580fba64eec18f62d2ce8',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F16VisitCollectionOracle.java': '2cbd4d9067a00caa6bc4c94137936d8d68e880fdb9e1c513ed4282785090ab6b',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlVisitCollectionCompatibilityTest.java': '9d9179a468a021d94347493a4d24ce7559fa71ee10ab9e7868a8659b08dde23d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '1309aa9842a35a9ff349c48b944d131bf4e5944778d59224ef0f3e203f94e5cd',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': '1db238567ec15f202c447b1f7d56c8275e75c348b6002342b8f38bbedf69858a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/VisitCollectionConfiguration.java': 'fbae2dc5a9ff22773dbe843e4a83dc0c9e64e6492ecc942bfd36e521f55f0070',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '57759b237d2e20c8a5607c4567e2b1aa94354784ddf24222a5522a425288edbf',
 'backend-app/boot/src/test/java/com/pawbridge/backend/VisitCollectionConfigurationTest.java': '1fea63fbf550b05a2c94fd82feebf50a072cbbdc2a41ca0209f885ab63f36ecd',
 'backend-app/boot/src/test/java/com/pawbridge/backend/VisitCollectionHttpTest.java': '218956473015ef344af513a39c46f8c9e8123eb1f5d363b273dd98deab7fc4af',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/application/VisitCollection.java': '48598d589c7de9be16a6d68a66babd0a4f01d8ead306aaa9e1843eec5545f648',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/application/VisitCollectionRejectedException.java': '8b180a43c9e2ad6708ca45733e6e7318c13629c41dc563a8edd9db014a06acd3',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/application/VisitCollectionStore.java': 'fa43789205d6af3651400ac40245b0d621c3f75223ee16eb9d4be9d17086fbc8',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/application/VisitIdentity.java': 'f82baafa315963d7667b7904390c030a42cbfb55d9ccc2d539c47a0586d74142',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/infrastructure/JdbcVisitCollection.java': 'd2e150dee0f6d316edc008934d1dbe4b500d255ba5c58f701580ce5cf1334284',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/infrastructure/JdbcVisitCollectionStore.java': 'c880cd0b01e48ca7bd499ea8b1166a73a1d5ff54b8c84b2cd0892f86d5ed2885',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/web/VisitCollectionController.java': '8cef77c073dd59e856eb88959a062ba1a5c5ba8f5c06fa114a6d5600dc18e013',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/visit/web/VisitCollectionExceptionHandler.java': '25b695f5d6721152e2db1fdb212f357718f3da167541554947eea9eb9a0405ad',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/visit/application/VisitCollectionTest.java': '60fe6146779d2690c54c3fea7dd4488a6918930281e2a71ed631b1606e547a40',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/visit/application/VisitIdentityTest.java': 'f1ca42cd42fe49519bdb5c9bcaf300768f7d4877cdca06028f42cc931d563c6e',
 'backend-app/scripts/f29_ci.py': 'a44e649a73f8201ac97df5132bb5db7804f9e30a33e9c7135c2e3f201e5f9e45',
 'backend-app/scripts/prepare_f16_legacy_oracle.py': 'c315deaa411d3664830f0a3c3d038a0e8959654479998202dbcd9043f2dd44eb',
 'backend-app/scripts/test_f16_ci_guards.py': '35ff03ffab8f52ac4e3926d6fbb290e9f4773a98a3e865452f150c1fe2e98470'}
VISIT_APP_EXCEPTIONS = frozenset(VISIT_APP_HASHES) | {'backend-app/README.md'}
VISIT_ALLOWED_PATHS = ADMIN_ALLOWED_PATHS | frozenset(VISIT_APP_HASHES) | {
    '.github/scripts/test_backend_visit_collection_profile.py',
}
VISIT_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'compile_visit_collection', 'fixture_member_cleanup',
                            'fixture_visit_collection', 'pg_visit_collection')
VISIT_FULL_CONTRACTS = ('fixture_admin_visit_collection', 'pg_visit_collection')
VISIT_SKIPPED_CONTRACTS = ('pg_note_expiry', 'pg_member_cleanup', 'pg_signup_statistics',
                           'compile_admin_update', 'fixture_admin_update', 'pg_admin_update',
                           'fixture_admin_visit_collection')
VISIT_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlVisitCollectionCompatibilityTest'
VISIT_SINGLE_METHODS = ('givenAMicrosecondCollectionStartAndSparseLeapDays__whenStartingTwiceAndReadingPeriodsInEitherZone__thenKeepTheFirstInstantAndZeroFillOnlyCollectedDays', 'givenNewAndRepeatedDayIdentities__whenCountingThenARealSqlFailureOccursAfterTheCte__thenDedupeAndRollbackBothPhysicalWritesWithEitherManager', 'givenOldAndCurrentVisitorRows__whenCleanupCrossesTheSeoulFiveMinuteGrace__thenCommitEachOldDayAndPreserveAllAggregatesAndStartMetadata', 'givenCleanupHoldsTheExclusiveOldDayLock__whenCountWaitsAcrossSeoulMidnight__thenRecheckTheDayAfterWaitingAndNeverRecreateTheCleanedIdentity')
VISIT_PARAMETER_METHODS = ('givenSharedDayLocksAndConcurrentIdentities__whenBothCount__thenSameIdentityDedupesAndDifferentIdentitiesIncrementWithoutLostUpdates', 'givenAnExactWriterPrivilegeRevoked__whenCountingOrCleaning__thenKeepTheWholePriorStateAndRestoreOnlyThatPrivilegeBeforeRecovery')
VISIT_REPORT_CASES = tuple(name + '()' for name in VISIT_SINGLE_METHODS) + tuple(
    name + '[' + str(index) + ']' for name, count in zip(VISIT_PARAMETER_METHODS, (2, 3))
    for index in range(1, count + 1))
VISIT_SEOUL_METHOD = 'givenAMicrosecondCollectionStartAndSparseLeapDays__whenStartingTwiceAndReadingPeriodsInEitherZone__thenKeepTheFirstInstantAndZeroFillOnlyCollectedDays'


AUTH_SESSIONS = 'auth-sessions'
# Future Visitor feature head is unconfirmed. Root must replace this before publication.
AUTH_BASE = '4afaae145d48aab153147515687a518a3c654c50'
AUTH_APP_HASHES = {'backend-app/boot/build.gradle': 'b55126579bf4dbe023d3b902cde581de1bb64cbfd16121522880999a103f65c1',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F13LegacyNicknameOracle.java': '1426ace48a10528820b4d77d9a1f107a044a899b530dbe662e3392d5f39cb8a4',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlAuthSessionCompatibilityTest.java': '5c2999519d8e0f923d7db273c879633b0017ce7276a0bdd234148e049ebf53d2',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': 'ae69ef2af6733112754e401448f0f6df3d6a5239f532b16d0ba104fb2d49d9fc',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AuthSessionConfiguration.java': '744e524291706c8776ca1feb282b1f91c62b402f085356aabaf15f7d0342b4e5',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': '826a969854c20c5e655c1df5de7d92a2661bf3e18c2daa083cb34ded5aaebc53',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AuthSessionConfigurationTest.java': '71fcc30f3ea2672aa744a733d01e95da5a89c7d0b341282af18d69938a8c69c5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AuthSessionHttpTest.java': 'e10a7528d9ae681f64cf3c6489dca6ae3617c26d7736a86aca8b2cc38c6a47d6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '5f53b3946e628db01de51e65a56c7023406b5b1d9951f331fdd14c4890c3ffaf',
 'backend-app/scripts/f29_ci.py': '0a2ef3e499de41cc3283e50d30fe1b590d03feb777c4c297272ea1cb8a0f8ee5',
 'backend-app/scripts/prepare_f13_legacy_oracle.py': '3e2efdddf404acf473be918c1978d4e41d5393d3a0ef761ed3369f2d92f973be',
 'backend-app/scripts/test_f13_ci_guards.py': 'd9dbbd6168aa6e5c15a64af5e53321e53a23c9f4c00079c73e3f8b2448fc2300',
 'backend-app/scripts/test_f29_ci_guards.py': '22fab98283f57021ff9b297a7acb387dc33286b37991cf197c4d44c39205a1a1',
 'backend-app/user/build.gradle': 'e6e67564e8a003e76a2fe0ddf5d08b0c3fead5b350bd8f1e2b20643d4b898d0a',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/application/AuthSessionRejectedException.java': 'a3dc6fe6ff2b82cbc50ca23812aa1bcb87e4f89f072b12c91ec6cc1b1276b4f7',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/application/AuthSessions.java': 'a46b8769fa1f7d8a7072e386f78df50463cb41b2e0d47a64f7475528d0b70559',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/infrastructure/JdbcAuthSessions.java': '45b18f42126776200bc0c458f077ff0110655c853c826cd619991a78a131030a',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/infrastructure/JwtSessionTokens.java': '060302527670a7296d2c9ece4fb060d9ea47d8dc2070f5540de5edbc726215d2',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/web/AuthSessionController.java': '05e324a149c6b2ae20690034df31539203aa263281e592f74a3397c99561d0d6',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/web/AuthSessionExceptionHandler.java': 'e52fcb42a85d00ec8d06799fac8e5928a16dc02c4aacce6b7d4b8e87a2592396',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/session/web/RefreshSessionRequest.java': '2fe4538058ad6212b95828eb46c80598a50e5d191ad22ca4350efb7baa1f250a',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/session/infrastructure/JdbcAuthSessionsTest.java': '6f9021bc883071a63a12d01151e7b382e9dcad991603c806579b6b8d05c29676',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/session/infrastructure/JwtSessionTokensTest.java': '0610aa83cb8ac9938079628c9626caeadfec763bf28926b26aa0dc8a030b24bd'}
AUTH_APP_EXCEPTIONS = frozenset(AUTH_APP_HASHES) | {'backend-app/README.md'}
AUTH_ALLOWED_PATHS = VISIT_ALLOWED_PATHS | frozenset(AUTH_APP_HASHES) | {
    '.github/scripts/test_backend_auth_sessions_profile.py',
}
AUTH_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'compile_auth_sessions', 'fixture_member_cleanup',
                           'fixture_admin_update', 'fixture_auth_sessions', 'pg_auth_sessions',
                           'restore_auth_sessions')
AUTH_FULL_CONTRACTS = ('compile_auth_sessions', 'fixture_auth_sessions', 'pg_auth_sessions', 'restore_auth_sessions')
AUTH_SKIPPED_CONTRACTS = ('pg_note_expiry', 'pg_member_cleanup', 'pg_admin_update', 'pg_signup_statistics',
                          'compile_admin_update', 'compile_visit_collection', 'fixture_visit_collection',
                          'fixture_admin_visit_collection', 'pg_visit_collection')
AUTH_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlAuthSessionCompatibilityTest'
AUTH_METHOD_CASES = (('givenCurrentClaimsAndMicrosecondStoredExpiry__whenRotatingThenReplaying__thenPreserveCreatedAtAndOnlyChangeTheSelectedRefresh',
  1),
 ('givenRejectedRefreshInputs__whenCallingTheTransactionalSourceAndCandidate__thenPreservePrecedenceAndNoMutation',
  4),
 ('givenLogoutSelectionAndAnIndependentLaterInsert__whenDeletingByUser__thenRemoveOnlySelectedRowsWithoutReadingUser',
  3),
 ('givenAnAlreadyLoadedRefreshDisappearsInAnotherTransaction__whenUpdatingOrDeletingIt__thenBothFailAndRollbackRemainingSelectedRows',
  2),
 ('givenBothRequestsReadTheSameRefreshBeforeEitherWrites__whenRotatingWithoutVersionOrCas__thenBothMayCommitAndTheLastStoredTokenIsOneReturnedValue',
  2),
 ('givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCallingThenRestoring__thenFailServerSideWithoutPartialMutationAndRecover',
  3),
 ('givenAnOwnedDeferredConstraintRejectsTheRefreshUpdate__whenCommitting__thenBothExposeServerFailureAndRestoreTheEntireSnapshot',
  2))
AUTH_REPORT_CASES = (AUTH_METHOD_CASES[0][0] + '()',) + tuple(
    method + '[' + str(index) + ']' for method,count in AUTH_METHOD_CASES[1:] for index in range(1,count+1))
AUTH_SEOUL_METHOD = 'givenCurrentClaimsAndMicrosecondStoredExpiry__whenRotatingThenReplaying__thenPreserveCreatedAtAndOnlyChangeTheSelectedRefresh'

POST_STATISTICS = 'admin-post-statistics'
# Future Auth feature head is not fixed; root replaces this and the matching new test BASE before publication.
POST_STATISTICS_BASE = '38012acfa938cdb2b0edcf1fead0144b8579275b'
POST_STATISTICS_APP_HASHES = {'backend-app/boot/build.gradle': 'def6462292b64a854c9fe2e7c63c71948d078a1f09c84d3ba36d4c24292df03d',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F6LegacyAdminPostStatisticsOracle.java': '333ae5fecefb65e0aedf48b5758b6134e9b89cd5b7bab90a8c13a6d90dba5beb',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlAdminPostStatisticsCompatibilityTest.java': 'cd4262da5bde16716a9cc1b40c4a4cf6104f48da3e5c96ab4ff329e706907a1c',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '2ee8d8de090d7e46fe296c8f72d472705c6e02ba9493527e2d0589344fa0416c',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AdminPostStatisticsConfiguration.java': 'cd06807cbe0cc7b677ba1f693cf535b77248166b391845cc386631c5d3d017f8',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': 'fbe664b370492ddc5e7a737ca2f6b555ccfa85078bf381652abe40ee720a85b7',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostStatisticsHttpTest.java': 'bf693d6b96aaaaad53f27a02f809d774676a76d41f83521ef07ebb5353332030',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '14ae3e615c399c70cf55ed5e44eaf4caf1519330146e7e893dbf32811c7e52a2',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/AdminPostStatisticsQuery.java': 'c2cc087943c731c1f9a3db0d1f3c8bdd2c63a8ea9d9c791fb0172184d7da89b8',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcAdminPostStatisticsQuery.java': '80cbd39c3b351e036d3cd013bb3fa88479ad06f8545b6db9ccbc8b6e63a9d9b4',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostStatisticsController.java': '1a2a78c8d44cb0cdff4dc9d20e326e1b03a554b456a6c16bf98a8ef39d57ca8e',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostStatisticsExceptionHandler.java': '82b7f75c40de08105656dfd25268255cd53aae6f2887bede4e4fa4a7bd93b83f',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcAdminPostStatisticsQueryTest.java': 'd687a3020cc5e2caef7fa50029fcbe9193026ecfcab76a07f5d28be6bc0ebd38',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/web/AdminPostStatisticsExceptionHandlerTest.java': '253449b8359448f5bd82bc931c091804ca10fc9838984f9d00844c6263670b55',
 'backend-app/scripts/prepare_f6_legacy_oracle.py': '94a87a3daaa889f3b4454e5655d4ea718063a5cddbc9967e204e266d767293c5',
 'backend-app/scripts/test_f6_admin_post_statistics_oracle.py': '70b8b89e25bafad54397fdcf320ee6ddc1db085e68e02f3e4c7e4140c6c3ae5c'}
POST_STATISTICS_APP_EXCEPTIONS = frozenset(POST_STATISTICS_APP_HASHES) | {'backend-app/README.md'}
POST_STATISTICS_ALLOWED_PATHS = AUTH_ALLOWED_PATHS | frozenset(POST_STATISTICS_APP_HASHES) | {
    '.github/scripts/test_backend_admin_post_statistics_profile.py',
}
POST_STATISTICS_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'compile_post_statistics', 'pg_post_statistics')
POST_STATISTICS_FULL_CONTRACTS = ('compile_post_statistics', 'pg_post_statistics')
POST_STATISTICS_SKIPPED_CONTRACTS = ('pg_note_expiry',
 'compile_admin_update',
 'compile_visit_collection',
 'compile_auth_sessions',
 'fixture_member_cleanup',
 'pg_member_cleanup',
 'fixture_admin_update',
 'pg_admin_update',
 'fixture_auth_sessions',
 'pg_auth_sessions',
 'restore_auth_sessions',
 'fixture_visit_collection',
 'fixture_admin_visit_collection',
 'pg_visit_collection',
 'pg_signup_statistics',
 'fixture_post_unrelated_f8',
 'fixture_post_unrelated_f9',
 'fixture_post_unrelated_f10',
 'fixture_post_unrelated_f11',
 'fixture_post_unrelated_f12',
 'fixture_post_unrelated_f13',
 'fixture_post_unrelated_f14',
 'fixture_post_unrelated_f15',
 'fixture_post_unrelated_f16',
 'fixture_post_unrelated_f17',
 'fixture_post_unrelated_f18',
 'fixture_post_unrelated_f19',
 'fixture_post_unrelated_f20',
 'fixture_post_unrelated_f21',
 'fixture_post_unrelated_f22',
 'fixture_post_unrelated_f23',
 'fixture_post_unrelated_f24',
 'fixture_post_unrelated_f25',
 'fixture_post_unrelated_f26',
 'fixture_post_unrelated_f27',
 'fixture_post_unrelated_f28',
 'fixture_post_unrelated_f29')
POST_STATISTICS_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlAdminPostStatisticsCompatibilityTest'
POST_STATISTICS_SINGLE_METHODS = ('givenSparseLeapDaysMicrosecondsAndTheJvmToday__whenReadingInEitherZone__thenMatchActualJpaGroupingBoundsAndSoftDeleteWithoutFillingGaps', 'givenTheBoardColumnRevoked__whenTheSecondQueryFailsThenPermissionIsRestored__thenReturnNoPartialDataAndRecoverWithoutAnyDatabaseDelta')
POST_STATISTICS_PARAMETER_METHOD = 'givenAnOutsideCommitBetweenTheTwoAggregates__whenReadingThePeriod__thenBothActualTransactionManagersKeepTheFirstRepeatableReadSnapshot'
POST_STATISTICS_REPORT_CASES = (POST_STATISTICS_SINGLE_METHODS[0] + '()',) + tuple(
    POST_STATISTICS_PARAMETER_METHOD + '[' + str(index) + ']' for index in (1,2)) + (POST_STATISTICS_SINGLE_METHODS[1] + '()',)
POST_STATISTICS_SEOUL_METHOD = POST_STATISTICS_SINGLE_METHODS[0]

POST_READS = 'admin-post-management'
# The actual PostStats feature head is not fixed. Root replaces this and the matching test BASE before publication.
POST_READS_BASE = '990e7b3459dbbefb7c2bf877b6a2cf0891d54e68'
POST_READS_APP_HASHES = {'backend-app/boot/build.gradle': '9715076a40a1567faf7b4ee3f0a20052e82d80b5f469986048b1f2a7e36abe36',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F5LegacyPostOracle.java': 'e253a659bdb66826d97404a44bafb0bc4af7f0bd372838db892e8f2ef673cd01',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlAdminPostReadCompatibilityTest.java': 'e9dcb46dae6716a6ae3a7998ae64ce69fb458a11c082c85cd8bbc3f4415c2127',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '3fc1087c0a510723172c84b236aebfe5280a2b97d17ab48d64e4c27b0341b7b9',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AdminPostReadConfiguration.java': 'c1553ebbb7e6c4ed39ff4170f9f801f4566763faa053ee3ecbb672525abe4dbc',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/PostWriteConfiguration.java': 'c7430dfd017008c3f65ddc7e92209a6a4a65d07421264a78f50b0407d0e2a6c9',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': 'eea8e296ef5a2bd7bdd821543df4d70d7af194a250a77f3930f6fda5a218ca95',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostMutationHttpTest.java': 'feca1b7a4f4f7c6cb6c97d06157ef1cea8005722b64c1a90258df18af4ca18fb',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostReadHttpTest.java': 'ee591506da75a0841f5322f7d3d5f2db6a1654a5034c1d70b1a88bd8cced891b',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostStatisticsHttpTest.java': '8042c41ce6a179c48aa170fd448f2769c948efc5f2d66a9c59d86abef42cb93b',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '423c26d89ab90c7c963adfa30396ea0178717e8e4711cd0f26ceaff413a209de',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostDeleteHttpTest.java': 'ca8bec58cfbc8251b772cbcaaef91d8dfa14d5d525dc9510440c39df7e2d1cd6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostListHttpTest.java': 'c23bff985fd2dc3a8ef48cc5d9f4bb2d6bfecc91d69cc985cb0d74c4ddbb2f37',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostSearchHttpTest.java': '009c20bb4daf58dba55e2ae670eb2c8c77723f37b3f9f72ae30a726a0caabae8',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostUpdateHttpTest.java': '35b3fb4b30680e8854a7b864e14debe6203cb240de14106c07c7b89cd3f7dd40',
 'backend-app/community/build.gradle': 'bfdd6dc1a1a1e17bd0f3254ed02727c2071f778f2016d8ead39c97b51d980b7a',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/DeletePost.java': '48910eea5c1d148dde25a2c5b1509f7f162ab45de22f311589941377a6049c8a',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/PostPageReader.java': '31eaad2d4f714300efcd91428a0dc33329fb4f1049733812cbfd9a53d3fd1137',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/ReadAdminPosts.java': 'ac9502c3db7e87e0cc2bde72d040ecffea00b7c8762cb6a5e9403b3641e8a702',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/ReadPost.java': 'bfbe086e02db7f5e5f347dd93e642be7cccde9a1000e774cc73a668fac9e1831',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/application/UpdatePost.java': 'ae391245bb52759341ba735cf998b428760ce6c3a26666d54df9c2f855720862',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcPostReader.java': '23086ae32ea1192b0d1ddec302fae44ee491d94a60866cdcbd277ca702dc5c84',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostDeleteController.java': 'ab25ce1ce8de12548c42926c00dc0f02fe4f14cb9e08bb25a0845711bbdcf324',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostReadController.java': '13ec438a6ffb0bb5422a96d7468af96031626a88dbcec493340e2dbfc23f4691',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostReadExceptionHandler.java': '79032b7b37b6c8022468f7e127adbb49afe041f6139d69967a279c2c101010cb',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/AdminPostUpdateController.java': 'eb13dc45bba0fdd17db69d61f0c3cbde162199a7d1ff787d01c6e2cafeb9d207',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/PostDeleteExceptionHandler.java': '6c7793adfaba00f401fbf125d7e096528f2b3f6ad0bd9a042ab44eb67973754e',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/web/PostUpdateExceptionHandler.java': 'e0697043fe9073aaeb18e7e7a5a74a5bf5681c5efc98870155b8a746326dc1bb',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/DeletePostTest.java': '6425b8aae237ee816f521cb6ff2462d9fd19b970c0d581482f864dc47da0948a',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/ReadAdminPostsTest.java': 'fab415c6b47b2a8458d3f52d97e3a3e92ad10a14efc7fec8b311517a839cadb5',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/UpdatePostTest.java': 'e0841d5708df3d033cf4c5d909859d6210bff1aa4c3591fc17d73f95e0ddd001',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcAdminPostPageReaderTest.java': '9e7d26b505d8f7af1286b588938b4126230771b94060c180a66563bd01e7d1b3'}
POST_READS_APP_EXCEPTIONS = frozenset(POST_READS_APP_HASHES) | {'backend-app/README.md'}
POST_READS_ALLOWED_PATHS = POST_STATISTICS_ALLOWED_PATHS | frozenset(POST_READS_APP_HASHES) | {
    '.github/scripts/test_backend_admin_post_reads_profile.py',
}
POST_READS_REQUIRED_CONTRACTS = ('fixture_f4_shape', 'compile_post_reads', 'pg_post_reads')
POST_READS_FULL_CONTRACTS = ('compile_post_reads', 'pg_post_reads')
POST_READS_SKIPPED_CONTRACTS = POST_STATISTICS_SKIPPED_CONTRACTS + POST_STATISTICS_FULL_CONTRACTS
POST_READS_REPORT_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlAdminPostReadCompatibilityTest'
POST_READS_METHOD_CASES = (('givenOwnedActiveAndDeletedPosts__whenPagingWithTheActualRepository__thenMatchRowsTotalsCountElisionAndNineFields',
  3),
 ('givenMappedStringNullableJsonAndUnknownSorts__whenReading__thenKeepActualPostgresOrderingOrFailureWithoutInventingTextCasts',
  4),
 ('givenExactlyTheExistingReaderColumnsRevoked__whenContentOrCountFails__thenBothManagersRaise42501WithoutPartialRowsAndRecover',
  4))
POST_READS_REPORT_CASES = tuple(method + '[' + str(index) + ']' for method, count in POST_READS_METHOD_CASES for index in range(1, count + 1))

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


def verify_admin_member_update_inputs(git_reader=None):
    """Accept only the final reviewed application/compiler/fixture bytes on the signup parent."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if len(ADMIN_APP_HASHES) != 20:
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', ADMIN_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(ADMIN_APP_EXCEPTIONS):
            return False
        for path, digest in ADMIN_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_visit_collection_inputs(git_reader=None):
    """Accept only the fixed visit application/compiler/fixture bytes on the administrator parent."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if len(VISIT_APP_HASHES) != 22:
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', VISIT_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(VISIT_APP_EXCEPTIONS):
            return False
        for path, digest in VISIT_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_auth_sessions_inputs(git_reader=None):
    """Accept only the fixed auth application/compiler/fixture bytes on the confirmed Visitor parent."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if (not re.fullmatch(r'[0-9a-f]{40}', AUTH_BASE) or AUTH_BASE == '0' * 40
                or len(AUTH_APP_HASHES) != 23):
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', AUTH_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(AUTH_APP_EXCEPTIONS):
            return False
        for path, digest in AUTH_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_post_statistics_inputs(git_reader=None):
    """Accept only the fixed auth application/compiler/fixture bytes on the confirmed Visitor parent."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if (not re.fullmatch(r'[0-9a-f]{40}', POST_STATISTICS_BASE) or POST_STATISTICS_BASE == '0' * 40
                or len(POST_STATISTICS_APP_HASHES) != 16):
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', POST_STATISTICS_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(POST_STATISTICS_APP_EXCEPTIONS):
            return False
        for path, digest in POST_STATISTICS_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False


def verify_admin_member_update_report(directory):
    """Check all sixteen unique, source-pinned parameterized method/index labels."""
    try:
        directory = Path(directory)
        reports = list(directory.glob('TEST-*.xml'))
        if directory.is_symlink() or len(reports) != 1 or reports[0].is_symlink():
            raise ProfileBlocked('The administrator update report is missing or redirected.')
        suite = ET.parse(reports[0]).getroot()
        cases = list(suite.iter('testcase'))
        names = [case.attrib.get('name', '') for case in cases]
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != ADMIN_REPORT_CLASS
                or int(suite.attrib.get('tests', -1)) != 16 or len(cases) != 16
                or len(suite.findall('testcase')) != 16
                or len(set(names)) != 16 or set(names) != set(ADMIN_REPORT_CASES)
                or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                       for key in ('failures', 'errors', 'skipped', 'disabled'))
                or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                or any(case.attrib.get('classname') != ADMIN_REPORT_CLASS or
                       case.attrib.get('status', 'run') not in ('run', 'passed') for case in cases)):
            raise ProfileBlocked('The administrator update cases did not all execute successfully.')
    except (OSError, ValueError, TypeError, ET.ParseError):
        raise ProfileBlocked('The administrator update report is invalid.') from None


def verify_visit_collection_report(utc_directory, seoul_directory):
    """Require all nine method/index labels and only the designated Seoul boundary case."""
    for folder, expected in ((utc_directory, VISIT_REPORT_CASES),
                             (seoul_directory, (VISIT_SEOUL_METHOD + '()',))):
        try:
            directory = Path(folder).absolute()
            reports = list(directory.glob('TEST-*.xml'))
            if (directory.is_symlink() or directory.resolve() != directory or len(reports) != 1
                    or reports[0].is_symlink() or reports[0].resolve() != reports[0]
                    or reports[0].name != 'TEST-' + VISIT_REPORT_CLASS + '.xml'):
                raise ProfileBlocked('The visit collection report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != VISIT_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
                    or len(suite.findall('testcase')) != len(expected)
                    or len(set(names)) != len(expected) or set(names) != set(expected)
                    or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != VISIT_REPORT_CLASS
                           or case.attrib.get('status', 'run') not in ('run', 'passed')
                           or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                           for case in cases)):
                raise ProfileBlocked('The visit collection cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The visit collection report is invalid.') from None


def verify_auth_sessions_report(utc_directory, seoul_directory):
    """Require all seventeen method/index labels and only the designated Seoul boundary case."""
    for folder, expected in ((utc_directory, AUTH_REPORT_CASES),
                             (seoul_directory, (AUTH_SEOUL_METHOD + '()',))):
        try:
            directory = Path(folder).absolute()
            reports = list(directory.glob('TEST-*.xml'))
            if (directory.is_symlink() or directory.resolve() != directory or len(reports) != 1
                    or reports[0].is_symlink() or reports[0].resolve() != reports[0]
                    or reports[0].name != 'TEST-' + AUTH_REPORT_CLASS + '.xml'):
                raise ProfileBlocked('The auth session report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != AUTH_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
                    or len(suite.findall('testcase')) != len(expected)
                    or len(set(names)) != len(expected) or set(names) != set(expected)
                    or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != AUTH_REPORT_CLASS
                           or case.attrib.get('status', 'run') not in ('run', 'passed')
                           or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                           for case in cases)):
                raise ProfileBlocked('The auth session cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The auth session report is invalid.') from None


def verify_post_reads_inputs(git_reader=None):
    """Accept only the fixed auth application/compiler/fixture bytes on the confirmed Visitor parent."""
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    try:
        if (not re.fullmatch(r'[0-9a-f]{40}', POST_READS_BASE) or POST_READS_BASE == '0' * 40
                or len(POST_READS_APP_HASHES) != 32):
            return False
        raw = git('diff', '--name-only', '--no-renames', '-z', POST_READS_BASE, 'HEAD', '--', 'backend-app')
        if not isinstance(raw, bytes):
            return False
        paths = [value.decode('utf-8') for value in raw.split(b'\0') if value]
        if len(paths) != len(set(paths)) or not set(paths).issubset(POST_READS_APP_EXCEPTIONS):
            return False
        for path, digest in POST_READS_APP_HASHES.items():
            blob = git('show', 'HEAD:' + path)
            if not isinstance(blob, bytes) or hashlib.sha256(blob).hexdigest() != digest:
                return False
        return True
    except (OSError, subprocess.SubprocessError, UnicodeError, ValueError, TypeError):
        return False



def verify_admin_post_statistics_report(utc_directory, seoul_directory):
    """Require all four method/index labels and only the designated Seoul boundary case."""
    for folder, expected in ((utc_directory, POST_STATISTICS_REPORT_CASES),
                             (seoul_directory, (POST_STATISTICS_SEOUL_METHOD + '()',))):
        try:
            directory = Path(folder).absolute()
            reports = list(directory.glob('TEST-*.xml'))
            if (directory.is_symlink() or directory.resolve() != directory or len(reports) != 1
                    or reports[0].is_symlink() or reports[0].resolve() != reports[0]
                    or reports[0].name != 'TEST-' + POST_STATISTICS_REPORT_CLASS + '.xml'):
                raise ProfileBlocked('The administrator post statistics report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != POST_STATISTICS_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
                    or len(suite.findall('testcase')) != len(expected)
                    or len(set(names)) != len(expected) or set(names) != set(expected)
                    or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != POST_STATISTICS_REPORT_CLASS
                           or case.attrib.get('status', 'run') not in ('run', 'passed')
                           or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                           for case in cases)):
                raise ProfileBlocked('The administrator post statistics cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The administrator post statistics report is invalid.') from None


def verify_admin_post_reads_report(directory):
    """Require every frozen UTC method/index label."""
    for folder, expected in ((directory, POST_READS_REPORT_CASES),):
        try:
            directory = Path(folder).absolute()
            reports = list(directory.glob('TEST-*.xml'))
            if (directory.is_symlink() or directory.resolve() != directory or len(reports) != 1
                    or reports[0].is_symlink() or reports[0].resolve() != reports[0]
                    or reports[0].name != 'TEST-' + POST_READS_REPORT_CLASS + '.xml'):
                raise ProfileBlocked('The administrator post reads report is missing or redirected.')
            suite = ET.parse(reports[0]).getroot()
            cases = list(suite.iter('testcase'))
            names = [case.attrib.get('name', '') for case in cases]
            if (suite.tag != 'testsuite' or suite.attrib.get('name') != POST_READS_REPORT_CLASS
                    or int(suite.attrib.get('tests', -1)) != len(expected) or len(cases) != len(expected)
                    or len(suite.findall('testcase')) != len(expected)
                    or len(set(names)) != len(expected) or set(names) != set(expected)
                    or any(int(suite.attrib.get(key, -1)) != 0 for key in ('failures', 'errors', 'skipped'))
                    or any(int(node.attrib.get(key, 0)) != 0 for node in suite.iter()
                           for key in ('failures', 'errors', 'skipped', 'disabled'))
                    or any(node.tag in ('failure', 'error', 'skipped', 'disabled') for node in suite.iter())
                    or any(case.attrib.get('classname') != POST_READS_REPORT_CLASS
                           or case.attrib.get('status', 'run') not in ('run', 'passed')
                           or case.attrib.get('result', 'completed') not in ('completed', 'passed', 'success')
                           for case in cases)):
                raise ProfileBlocked('The administrator post reads cases did not all execute successfully.')
        except (OSError, ValueError, TypeError, ET.ParseError):
            raise ProfileBlocked('The administrator post reads report is invalid.') from None



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
    if requested not in (None, '', FULL, PILOT, NOTE_EXPIRY, MEMBER_MANAGEMENT, SIGNUP_STATISTICS, ADMIN_MEMBER_UPDATE, VISIT_COLLECTION, AUTH_SESSIONS, POST_STATISTICS, POST_READS):
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
    admin_scoped = (context.get('event_name') in ('pull_request', 'push')
                    and context.get('repository') == REPOSITORY
                    and context.get('head_repository') == REPOSITORY
                    and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                    and context.get('admin_member_update_scope_verified') is True
                    and bool(paths) and set(paths).issubset(ADMIN_ALLOWED_PATHS))
    if requested == ADMIN_MEMBER_UPDATE and not admin_scoped:
        raise ProfileBlocked('The requested administrator update scope differs from its reviewed inputs.')
    if admin_scoped and requested in (None, '', ADMIN_MEMBER_UPDATE):
        return [ADMIN_MEMBER_UPDATE]
    visit_scoped = (context.get('event_name') in ('pull_request', 'push')
                    and context.get('repository') == REPOSITORY
                    and context.get('head_repository') == REPOSITORY
                    and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                    and context.get('visit_collection_scope_verified') is True
                    and bool(paths) and set(paths).issubset(VISIT_ALLOWED_PATHS))
    if requested == VISIT_COLLECTION and not visit_scoped:
        raise ProfileBlocked('The requested visit collection scope differs from its reviewed inputs.')
    if visit_scoped and requested in (None, '', VISIT_COLLECTION):
        return [VISIT_COLLECTION]
    auth_scoped = (context.get('event_name') in ('pull_request', 'push')
                   and context.get('repository') == REPOSITORY
                   and context.get('head_repository') == REPOSITORY
                   and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                   and context.get('auth_sessions_scope_verified') is True
                   and bool(paths) and set(paths).issubset(AUTH_ALLOWED_PATHS))
    if requested == AUTH_SESSIONS and not auth_scoped:
        raise ProfileBlocked('The requested auth session scope differs from its reviewed inputs.')
    if auth_scoped and requested in (None, '', AUTH_SESSIONS):
        return [AUTH_SESSIONS]
    post_scoped = (context.get('event_name') in ('pull_request', 'push')
                   and context.get('repository') == REPOSITORY
                   and context.get('head_repository') == REPOSITORY
                   and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                   and context.get('post_statistics_scope_verified') is True
                   and bool(paths) and set(paths).issubset(POST_STATISTICS_ALLOWED_PATHS))
    if requested == POST_STATISTICS and not post_scoped:
        raise ProfileBlocked('The requested post statistics scope differs from its reviewed inputs.')
    if post_scoped and requested in (None, '', POST_STATISTICS):
        return [POST_STATISTICS]
    post_reads_scoped = (context.get('event_name') in ('pull_request', 'push')
                   and context.get('repository') == REPOSITORY
                   and context.get('head_repository') == REPOSITORY
                   and (context.get('event_name') != 'push' or context.get('head_ref') == 'dev')
                   and context.get('post_reads_scope_verified') is True
                   and bool(paths) and set(paths).issubset(POST_READS_ALLOWED_PATHS))
    if requested == POST_READS and not post_reads_scoped:
        raise ProfileBlocked('The requested post reads scope differs from its reviewed inputs.')
    if post_reads_scoped and requested in (None, '', POST_READS):
        return [POST_READS]
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
    if profile == POST_READS:
        return ()
    if profile == POST_STATISTICS:
        return ()
    if profile == AUTH_SESSIONS:
        return ()
    if profile == VISIT_COLLECTION:
        return ()
    if profile == ADMIN_MEMBER_UPDATE:
        return ()
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
        'selection_reason': ('fixed-post-management-inputs-and-scoped-read-postgresql' if profile == POST_READS else
                             'fixed-post-statistics-inputs-and-scoped-postgresql' if profile == POST_STATISTICS else
                             'fixed-auth-inputs-and-scoped-postgresql' if profile == AUTH_SESSIONS else
                             'fixed-visit-inputs-and-scoped-postgresql' if profile == VISIT_COLLECTION else
                             'fixed-admin-update-inputs-and-scoped-postgresql' if profile == ADMIN_MEMBER_UPDATE else
                             'fixed-signup-inputs-and-scoped-postgresql' if profile == SIGNUP_STATISTICS else
                             'fixed-member-inputs-and-scoped-postgresql' if profile == MEMBER_MANAGEMENT else
                             'fixed-f30-inputs-and-scoped-postgresql' if profile == NOTE_EXPIRY else
                             'fixed-f25-ci-only-pilot' if profile == PILOT else 'full-reference-or-default'),
        'required_smokes': list(required),
        'skipped_smokes': [step for step in ALL_SMOKES if step not in required],
    }
    if profile == FULL:
        result['required_contracts'] = list(POST_READS_FULL_CONTRACTS + POST_STATISTICS_FULL_CONTRACTS + AUTH_FULL_CONTRACTS + VISIT_FULL_CONTRACTS)
    elif profile == POST_READS:
        result['required_contracts'] = list(POST_READS_REQUIRED_CONTRACTS)
        result['skipped_contracts'] = list(POST_READS_SKIPPED_CONTRACTS)
    elif profile == POST_STATISTICS:
        result['required_contracts'] = list(POST_STATISTICS_REQUIRED_CONTRACTS)
        result['skipped_contracts'] = list(POST_STATISTICS_SKIPPED_CONTRACTS)
    elif profile == AUTH_SESSIONS:
        result['required_contracts'] = list(AUTH_REQUIRED_CONTRACTS)
        result['skipped_contracts'] = list(AUTH_SKIPPED_CONTRACTS)
    elif profile == VISIT_COLLECTION:
        result['required_contracts'] = list(VISIT_REQUIRED_CONTRACTS)
        result['skipped_contracts'] = list(VISIT_SKIPPED_CONTRACTS)
    elif profile == NOTE_EXPIRY:
        result['required_contracts'] = ['fixture_f4_shape', 'pg_note_expiry']
    elif profile == MEMBER_MANAGEMENT:
        result['required_contracts'] = list(MEMBER_REQUIRED_CONTRACTS)
    elif profile == SIGNUP_STATISTICS:
        result['required_contracts'] = list(SIGNUP_REQUIRED_CONTRACTS)
    elif profile == ADMIN_MEMBER_UPDATE:
        result['required_contracts'] = list(ADMIN_REQUIRED_CONTRACTS)
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
    skipped_contracts = tuple(expected.get('skipped_contracts', ()))
    for key in ALL_SMOKES + contracts + skipped_contracts + ('owned_cleanup',):
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
    if skipped_contracts:
        result['skipped_contracts'] = list(skipped_contracts)
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
        identity['admin_member_update_scope_verified'] = verify_admin_member_update_inputs()
        identity['visit_collection_scope_verified'] = verify_visit_collection_inputs()
        identity['auth_sessions_scope_verified'] = verify_auth_sessions_inputs()
        identity['post_statistics_scope_verified'] = verify_post_statistics_inputs()
        identity['post_reads_scope_verified'] = verify_post_reads_inputs()
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
    parser.add_argument('--profile', choices=(FULL, PILOT, NOTE_EXPIRY, MEMBER_MANAGEMENT, SIGNUP_STATISTICS, ADMIN_MEMBER_UPDATE, VISIT_COLLECTION, AUTH_SESSIONS, POST_STATISTICS, POST_READS), required=True)
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
