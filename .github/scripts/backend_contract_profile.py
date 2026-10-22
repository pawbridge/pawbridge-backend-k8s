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

POST_READS = 'user-community-integration'
# The actual PostStats feature head is not fixed. Root replaces this and the matching test BASE before publication.
POST_READS_BASE = '990e7b3459dbbefb7c2bf877b6a2cf0891d54e68'
POST_READS_WORKFLOW_SHA256 = 'd9321b62cc0c148ecaec8e0592a1cb57fddf7a5fde5fcac4d57f7606f60d2987'
POST_READS_APP_HASHES = {'backend-app/boot/build.gradle': '8bbc11e2d1bb5db82d01eef7d3466f5ff57fec56f67823a7beff6284c5b9d7f6',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/F5LegacyPostOracle.java': 'e253a659bdb66826d97404a44bafb0bc4af7f0bd372838db892e8f2ef673cd01',
 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlAdminPostReadCompatibilityTest.java': 'e9dcb46dae6716a6ae3a7998ae64ce69fb458a11c082c85cd8bbc3f4415c2127',
 'backend-app/boot/src/main/java/com/pawbridge/backend/PawBridgeApplication.java': '30090d4e281fe5e0ad4159042199e12f32138125f624f87451af3e33a8807bbd',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AdminPostReadConfiguration.java': 'c1553ebbb7e6c4ed39ff4170f9f801f4566763faa053ee3ecbb672525abe4dbc',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AdminUserDirectoryConfiguration.java': '9722165e8be597e1f9a757666470f32e84d21deb7f26b0fccc64b9adb66f761d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalReportConfiguration.java': '82e4bbd9afb201dd3c963446424088d9ca1b5a48c76776c395809a6da01c01e3',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/GoogleOAuthBusinessConfiguration.java': '4ae9ab6484e3739f0705503101e2f9e7f60af6d04383e34c569b0dc439b6a6c0',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/GoogleOAuthConfiguration.java': '175385ef2bfb9c2cac0e0420e22a3fabff95b1e53757880e21605595b2c1823b',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/HomeVideoManagementConfiguration.java': '1fa46306495dfdabdd1a83bdd87545954533c5a46708bcdceb37e395ec2e44b1',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/MemberChatConfiguration.java': '081541c3df09529fbdafd4c16f1642e02ee87e9cf10362951c3332fb9bee6914',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/MemberChatSocketConfiguration.java': 'eb6b7740f6c4ebaa467541c56a7a54859e8ba83b7727b20b730325da71bc83ae',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/MemberChatTransportConfiguration.java': 'aa0c151315ac9d7d6e14003d0211a71808eca0be0e5661d03d0fea5588527f95',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/MemberCleanupConfiguration.java': '38763164bc1f510d7d1e019b5600cfa170e6f50b01471a6e020b9bc5b3364e2e',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/PostWriteConfiguration.java': 'c3e8c62ce0457be98e18cb4cbca31a762699f50ee396c678fcffd7382987362f',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java': 'fea5ae68cdf5894117d3a36af7e36105c1b8cd5162750a1215442912267b6b1a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ShelterMembershipConfiguration.java': '5998ca012795207e034ee0cb19e657820e98faf78609184b35f49171d612d5fc',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/UserAnimalActivitiesConfiguration.java': 'dda638d643fc686e44102314be4d0d6d88b36a07e910fd074615950d6a3f21d1',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/UserStoreMyPageConfiguration.java': '7016adb41e6e20e7400999aab42eabef66c18de3739b63878e22bf0cd889dd31',
 'backend-app/boot/src/main/resources/application.properties': '98b95621811fecccee450ba697537736c46ccf1c020b7ed8697e18ad8e6f981b',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostMutationHttpTest.java': 'feca1b7a4f4f7c6cb6c97d06157ef1cea8005722b64c1a90258df18af4ca18fb',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostReadHttpTest.java': 'ee591506da75a0841f5322f7d3d5f2db6a1654a5034c1d70b1a88bd8cced891b',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminPostStatisticsHttpTest.java': '8042c41ce6a179c48aa170fd448f2769c948efc5f2d66a9c59d86abef42cb93b',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDetailHttpTest.java': '9c5c898ed4999107da5541986d3ad93a80164301cda7eeaf4307f81e82e08bbf',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AdminUserDirectoryHttpTest.java': '2057dd858e2aecbfc3a1d32e045ff8c63eba8100746f6dd5a726818e632ebc75',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalReportHttpTest.java': 'd07e0ad965d8a5d6a1c651f4ba4d8d033c43964deba71a78ffb94fae9a5ee5a3',
 'backend-app/boot/src/test/java/com/pawbridge/backend/GoogleOAuthSecurityTest.java': 'd30ea15d65e9854f1d1f8eed747436480c90c5f9fa97044414a5bba4813906cf',
 'backend-app/boot/src/test/java/com/pawbridge/backend/HomeVideoManagementHttpTest.java': '1810556e11edddbeefbf9768889f2b893c8e508d4d81720f1fb50ecddf39d520',
 'backend-app/boot/src/test/java/com/pawbridge/backend/MemberChatProtocolTest.java': 'fdbeeba7aa7da996e0b40ce0fe71af8005ec0d8fe3418f3621a82584726934f3',
 'backend-app/boot/src/test/java/com/pawbridge/backend/MemberChatSessionLifecycleTest.java': '8e1d8a6b05a1b114461bcf39fa11c0e90500a407f079dd3a6252d3a55362edf0',
 'backend-app/boot/src/test/java/com/pawbridge/backend/MemberChatTransportHttpTest.java': '11fb099240fc857c3a0311ef90d5f70df04f291b3393440639e147a558543ecd',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeApplicationTest.java': '9f75b406f1b08a6e8929a8ba378c128d975ecbbfbe39c537581b78d190206381',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostDeleteHttpTest.java': 'ca8bec58cfbc8251b772cbcaaef91d8dfa14d5d525dc9510440c39df7e2d1cd6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostListHttpTest.java': 'c23bff985fd2dc3a8ef48cc5d9f4bb2d6bfecc91d69cc985cb0d74c4ddbb2f37',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostSearchHttpTest.java': '009c20bb4daf58dba55e2ae670eb2c8c77723f37b3f9f72ae30a726a0caabae8',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PostUpdateHttpTest.java': '35b3fb4b30680e8854a7b864e14debe6203cb240de14106c07c7b89cd3f7dd40',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterMembershipConfigurationTest.java': 'fe7c8891c0528dbedf9f3cb5c17330de83318a26b7fbb95d87832b082b8321a2',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterMembershipHttpTest.java': '10799ea8fe6d548d9b8507baeb40b9416aaa20397749dc983de411ede6fbfa3e',
 'backend-app/boot/src/test/java/com/pawbridge/backend/TotalUserCountHttpTest.java': 'a31d08c8cf26757474afca2e01b3b972840a4bf73b7590c8ffe995045d707505',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserAnimalActivitiesConfigurationTest.java': 'e82202d8df51d1e4141883b55f3e94f15b117c135084de3f9dc516fe5ced2d7f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserAnimalActivitiesHttpTest.java': 'ef5fa2af2acda98096162ac2aa061a842620ccd451c580fdefe83d60f52c7e88',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserStoreMyPageConfigurationTest.java': '79cab63162505134c4391b07e153ddbd803fef60bde57eff37f064e21accadf5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserStoreMyPageHttpTest.java': '3d006bace797b107446c091a9f417f76a618efcd8e4539228f8b8b1efb69d5df',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/GoogleOAuthBusinessConfigurationTest.java': '43749a7a5792d5ead06477bcf154186a23c4a3ea756f19016bae1801bb7c772f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/HomeVideoManagementConfigurationTest.java': '7093c4d81904e8b16d019e621399029903b3917198ca52dc2e58f1c8373498e4',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/MemberChatConfigurationTest.java': '132692a396854dffaeaf5a85ad2c9c6970290cafec8e13b553db5eafa91aac1f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/MemberCleanupConfigurationTest.java': 'fc2a4f6157c72bfa14a014d0180376bb14c0a677819b84670a19935c946a09ea',
 'backend-app/community/build.gradle': 'cae9d8a06febf4ceb5abe9a7728ed663ec07164883f85e01cba1f23fe3737408',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/application/MemberChat.java': '57288d68466f12437895e0b1fbbafe15d7aed4bda9a8af559f1a7a911c3a6823',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/application/MemberChatHints.java': 'a790c79cd485fc152e5f51031c6c744c5d21d8b64ea63654d65dc9111b40b7b6',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/application/MemberChatModels.java': '5f0eeb6751566891b0fa7fc188b8b58968bdc4b55e0a5c3172dcbf206d428a05',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/application/MemberChatRejectedException.java': '80bc4242197a21f2972f7696f12f198a772cffb6063897a0bd2345a9226a9f8f',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/infrastructure/JdbcMemberChat.java': 'eedc0663caf0c9bbe207831aeb62c1e0fe291e9745510ac53a95c68e4ea330dc',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/infrastructure/JdbcMemberChatRepository.java': '17d62cc9216da66a964eb6208fe73b5858d4b1774ec645496938f2d8eed5576c',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/infrastructure/MemberChatSignals.java': 'a7cd28293e771c09bdac6a91b22e4ec0123d2d74ef4e10efb56d7bb89555f59d',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/infrastructure/MemberChatTickets.java': '1a82dec4b07c879481ba09e47176dd334f555666492252506be1db8070476325',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/web/MemberChatController.java': 'd702e82368f2404d08bd2475e4cf5999e4031779d4e5267f01d3d51c8d7eadc7',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/web/MemberChatExceptionHandler.java': '24fbaeba3f60e28d60bc799c1fb3ffcce74e1b03a0c721d88fc9f0d4fb49d9b4',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/web/MemberChatProtocolErrors.java': '61b3c3f5c871b699745db7815b2834262484575e87bb077f574d0a8d6426b762',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/web/MemberChatSessions.java': '8154b526415e0ce8f498feccd0ccc27b29eea2e3c0c11fc1c3255534ea1146cb',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/chat/web/MemberChatSocketController.java': '75059da2a6e75f23ec0a748ec0e99c41b5385c75d07080227f027dda00f6d6c0',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/application/HomeVideoManagement.java': '3b93a4c2771cc1afd8e6ae9d3457e7c1ec6060d35fd99e3a530d95688768277b',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/JdbcHomeVideoManagement.java': 'bdd0550acc5f08f8be51078ec73f17f95419fe76557d22a9f6fd306ef7a9a1cb',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/JdbcHomeVideoRepository.java': '42f4f90b02fe7f283954456ed40c25fd28fa0dd4cc9ee8d5315aa60a2ddf7ff7',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/YouTubeVideoClient.java': 'd494a20273274bb007a45535f055be4ec47c22213ea553c3aa2d37125ca53f71',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/YouTubeVideoUrl.java': 'aeac786b2716d3e1bd5be1f5817cce419290d33e6d49a36320a778cde932ba4a',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/web/HomeVideoManagementController.java': '22003fb0fb9317ac0351b940c9ccbc8ee5405fc39b128910105a188153d3f9cc',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/homevideo/web/HomeVideoManagementExceptionHandler.java': 'f180db781f078c4e70f9770942fc9456d17b8354ed7474ec7b210067d89e5a4f',
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
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/report/application/AnimalReports.java': '7a0808d6047c797b9c3b419a1899afa043f7822978e5314a3a8ce641017eb290',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/report/infrastructure/JdbcAnimalReports.java': 'c9f990fa36cd6d213e277610bfada25d498476a51267a505ab986e1e2dca7718',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/report/infrastructure/S3AnimalReportMediaUploader.java': 'd66ccc8726326e9bb8eb1a9c79033a109d4afa4b5923c4eaf77b4d30c9bc849f',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/report/web/AnimalReportController.java': 'f1137ae510bce67341ede733b2cbc2cc8ef3a22dbd3c03ba3945c596d025331f',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/report/web/AnimalReportExceptionHandler.java': 'f4504610ab617eabebac0073eac20e2df2933d037b3af9aa7f9a1bfdc356efef',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/chat/infrastructure/JdbcMemberChatTest.java': '037479d70f31868dab5cdf497ba94ae5fa232270d20a92c8cf5ebbf47118ef30',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/chat/infrastructure/MemberChatSignalsTest.java': '7c7cb53e7a7fa894034f254a2b470d609aa718db85e471545c3263cc5a42cb0c',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/JdbcHomeVideoManagementTest.java': '40f5ff3232e363cc3d72bbac538a15d1ea8379e6267f48bf29b57a15889ab654',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/YouTubeVideoClientTest.java': '99402fbe0f16a98891438efb763ba80b2449be5e3035f2eb0b269e1a9a22c525',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/homevideo/infrastructure/YouTubeVideoUrlTest.java': '04a3b656bd7ebe50d2560179d3e8687d1884fea2993bce08504b803aebc572b6',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/DeletePostTest.java': '6425b8aae237ee816f521cb6ff2462d9fd19b970c0d581482f864dc47da0948a',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/ReadAdminPostsTest.java': 'fab415c6b47b2a8458d3f52d97e3a3e92ad10a14efc7fec8b311517a839cadb5',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/application/UpdatePostTest.java': 'e0841d5708df3d033cf4c5d909859d6210bff1aa4c3591fc17d73f95e0ddd001',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcAdminPostPageReaderTest.java': '9e7d26b505d8f7af1286b588938b4126230771b94060c180a66563bd01e7d1b3',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/report/infrastructure/JdbcAnimalReportsTest.java': '604609e895dbe7bbb38751f19e2856a921dec9c660c32739bdba9bf2dbb9e40a',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/report/infrastructure/S3AnimalReportMediaUploaderTest.java': '82d782b119f53b4800f22fa4ebf6dee3e77947b890748e8c8f789804236d7deb',
 'backend-app/scripts/test_f14_ci_guards.py': 'aba0d5b772835abaa770e3bed93a4290e74609200e5a67d290e7c90a5e6d04bd',
 'backend-app/scripts/test_f9_ci_guards.py': 'a5e7871329d57bbc640118e9ecad96a56b245aee3c3f0c42ed55c3db4a92a29c',
 'backend-app/user/build.gradle': '2618227c07294560b5e318808d990152272acef69c1a071d7dc2a44d656f0835',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/application/FavoriteActivityRejectedException.java': 'a4aabe74ab6720eaf0e2f83c57390a0018b5b7a936af5e59af2608e9ad7edc8b',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/application/UserAnimalActivities.java': '1aed0c2ecc8d25bb0c58d73f2c028c5d6d7a5d151d290120d0093e8f14f28947',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/HttpUserAnimalLookup.java': '1f01f52059753ba5e6fdfeadbbdaa7151e057e14f43693a154f7b94487abd6c9',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/JdbcUserAnimalActivities.java': 'af0ccf669e48356d0fe43edc250abfcb6153010f85f3b6dd48979f8033ea5309',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/web/UserAnimalActivitiesController.java': '4d74bd330efc247e8f99cd4483d753e1f0d261c914b818957bd3eb9c12387d40',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/web/UserAnimalActivitiesExceptionHandler.java': '216e752dea7236edc571fdd642fc5ee787abdafae3198a7acae530e3e32e7dd1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/directory/application/UserDirectoryQuery.java': 'a14826a42ad8d2f477cb7eb2a95fe1c40bf0d8cd11effc41a18c17c4ea2d6e2b',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/directory/infrastructure/JdbcUserDirectoryQuery.java': '02c234a2f08670136a7491c77c21995a84b7759bcd4a1f2007b0c09261131b8d',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/directory/web/AdminUserDirectoryController.java': 'ff0e6fe1059053762be1bf7d13db1f64642ab746bff5155782157ed4eb3ef17f',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/application/GoogleLogin.java': 'eb4a43463bf98a66697aa7af3040b9874968dc7f12384b59da44f75ca2b115db',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/application/GoogleLoginRejectedException.java': '908892ea7a8f2c13fd3d00ed09122e07e51dc38c46d0b761cff44b85fe0311e2',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/infrastructure/GoogleNicknameGenerator.java': 'ef45b2c7bb8d959c95f2d4136acb7279dd1fabcfac9fcc412eb1ea89836de3cd',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/infrastructure/GoogleOAuth2UserService.java': '3f69c3d717658cd5cf7cbb7d82bc4bb55a1fad3c55ae0f5b8b1640761ab6beff',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/infrastructure/GoogleOAuthPrincipal.java': '85fce8ebf34026391b662720bfe67796a8989ef7e8ad11aa38e18df9e51a69b6',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/infrastructure/JdbcGoogleLogin.java': '6f445b6d1d6c40d9ebdee82576edc68a9b4122d5dac564f92bd6b06d31ea7d68',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/web/GoogleOAuth2FailureHandler.java': '0e427a1ea64105f37c93238f03404eab84b196c50289ac09175ae4e97e5e2fd1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/oauth/web/GoogleOAuth2SuccessHandler.java': '7c89a5586d64760e3c776135eb36692df76b32e54033b75ecbdc69cd3216db1c',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/profile/web/UserProfileExceptionHandler.java': '19b0ed4e482d0a9df828580c22a5f4681b192f9845b30115c1f205c5c6ef7737',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/application/ShelterMembership.java': 'e7d6d353b925cc635fb91d55686a36da1d6e9c1e02b5c4ac567d8d50cab2bd89',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/application/ShelterMembershipRejectedException.java': '300027bd01b3e4bc3c56c4ac0bf8e847fdf1b000a5f2cc1e43d9dbc8220e7d80',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/infrastructure/HttpShelterLookup.java': '81c4896638dccf9eecfb533bcef2e7376a74c943e8cbaaed55344a8a53139830',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/infrastructure/JdbcShelterMembership.java': 'a0c93748e635c8fc71ef725ea23a933c84c66832a65dca124030d0bd63bd3068',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/web/AdminShelterApplicationController.java': 'ac52fded6ed4d7b37915c352347b7f19596893d69e951353ab526d935005a844',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/web/AdminShelterMemberController.java': '9c6c2f675e7e7d488a747c993de57bf8425269da5f4ff83283e2ae00928871f2',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/web/ShelterApplicationController.java': '1d3173c8942aacbe02782bc77286876dbeaf6b14b7f2f6e503e2a942c56c9b08',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/web/ShelterMembershipExceptionHandler.java': 'c109ab292033923fdc1d03884599ad0e11bcb208aed672e6530bbc9f2c7159c0',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storemypage/application/StoreMyPage.java': '4c35b5d47e2736c0be5c8b2f0558661a406d9396c05400065e7ff8d819577883',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storemypage/infrastructure/HttpStoreMyPage.java': 'ab17852c0ed974049b6283a1c24602d63c68b388ba43c25139a08254992d96b6',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storemypage/web/StoreMyPageController.java': 'bc74232c2b27b38a25b33b81e4e84ab7a78a9ae5a31b8f9b90d49513bd5974fa',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storemypage/web/StoreMyPageExceptionHandler.java': '142d96fb4089cdc4bc2163a204901b26d6b37fd8153791468a09459d4984e673',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/HttpUserAnimalLookupTest.java': '48b5088130a892d62cda8819860312c62babe89a90d3a70d3304c08570e76e63',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/JdbcUserAnimalActivitiesTest.java': '187b090266447d0f66809980501399f73c93ce3d5e96d40f5f76e0a52cd36d48',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/directory/infrastructure/JdbcUserDirectoryQueryTest.java': 'cac45bd629bad9e3740910cdd5ecc4c92b9c66cab614fcc46976f48811e82aa6',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/oauth/infrastructure/GoogleOAuth2UserServiceTest.java': '3672db348fdf86b8bf10d58f1e669a2956dfb3f9193002db08722d5ac074046c',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/oauth/infrastructure/JdbcGoogleLoginTest.java': '31d6268c525f3c5cc5aa90d6dccbd736d9becb41f6237c2dae283b511d29aa4e',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/oauth/web/GoogleOAuthHandlersTest.java': 'bf085676cd5195bfe652aa827703bd798747c7e7a855c1ba3a989aa826554825',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/shelter/infrastructure/HttpShelterLookupTest.java': '280304494a1bbbaf3fd71d7a8441d4af639a058284974e664e48d605b0aafe3e',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/shelter/infrastructure/JdbcShelterMembershipTest.java': '8401c06ec852ab363745e08500ad78a22be546651c9d2b836844d6b52c4f1a9f',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storemypage/infrastructure/HttpStoreMyPageTest.java': '2f36cb808f159f448809aecbb02282910cb55e5d40f7d729b0a943eaaeb5a09c',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storemypage/infrastructure/HttpStoreMyPageTransportTest.java': 'd662499bc8cf42d2a62cfbd86d3967515ff6ee1e42fcecf96356cec4c7e5834b',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storemypage/web/StoreMyPageControllerTest.java': '0757146f099d405f125d5666674eec33ee5bc9092df4237f9c1290b787a0c3a7',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcPostWriter.java': '5d408a29c245a44548df359ed616c555179ba5c94c17c13d30fb81f7842f05ad',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcPostSearchRebuild.java': 'eef810636d315a6f0be67a14330bb67cf7090a3f4fc0b7e9f6a705f692233017',
 'backend-app/community/src/main/java/com/pawbridge/backend/community/internal/maintenance/infrastructure/JdbcCommunityEventRetention.java': 'a318f63faf9e3b13db35173964ad3b26b003e0c9608d91bbe4ee14abaa6b4ca9',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/post/infrastructure/JdbcPostSearchRebuildTest.java': 'a20c3eff100843879e0dac55a902bafe02a5fb3edaa208f7f4b924ff4b529cdc',
 'backend-app/community/src/test/java/com/pawbridge/backend/community/internal/maintenance/infrastructure/JdbcCommunityEventRetentionTest.java': 'eda7ea8ce4d9cfb3d02cc3dd91dd33d4a29982f42957d3a8b2f5c93b390bd488',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/CommunityMaintenanceConfiguration.java': '52f43e0fdab58578e1041f4239d2c7b18217c721db7b30f81c033b047b7d9421',
 'backend-app/boot/src/test/java/com/pawbridge/backend/CommunityMaintenanceConfigurationTest.java': '619a039f36af66ffd14c8e7fc6bedd6748b3bf21ba2cdb339dcc2556290638f0',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/application/AnimalDiscovery.java': '676899b5503aabdcb76e0af21f90defd7f82b6a9a26cfb5abec09b7d12c06836',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/application/AnimalNotFoundException.java': '3338d8f9d4196c99f4d0e780e179be044a4fca0361d3ec5258ac29c3a2641350',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/application/IntakeDateRange.java': '09a24657b4438aa5eb3334cf11591efcb5f735a4d4cb177c265af22909744865',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/application/SearchProjectionPendingException.java': '0107ad697dc60f2a8ebe120a4191a5a8b0cede264ae8d972c79a115d3785c3c4',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/infrastructure/JdbcAnimalDiscovery.java': '77c4f11eb772f17a4cc8ffaa0521bc4b6162d268d421041047dac6c3e5d8d453',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/infrastructure/KoreanSearchAnalyzer.java': 'edff2e8ee97c73c63b79ce6aa561089edc9ef765beaa1fabc8d9a35022da57be',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalDetails.java': 'a95c517c6846f4ac9ad4555a8eb3581b0dc7effc55414e9d035131213ad50761',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalDiscoveryController.java': '7799708009ab933ed90e63ef8d22b1e972bece78b0c8950452e6af6f0dceb282',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalDiscoveryExceptionHandler.java': 'e884bd4b47d7c344833b52a2251d63b86ad34a143054a598e8aa27f0e196c299',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalSearchParameters.java': '72b2153fa776775d5de822201a66b80f0c4ab4d179a978db959b97f3ec7f1edc',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalSummary.java': 'ded4c98569700fb4a1f7d746ee17511eeee1a8e65a4a5eace8340258faf421cc',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/application/AnimalRecommendations.java': '6e41cb750b5ffeac0f089bdc05f04ef60090864e368c3afbc4f33edb142a7284',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/application/RecommendationUnavailableException.java': 'b9e3d6f588ed8bb73df51b68421fb3ec01eb665a809d648c47c1aab91de146f9',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/infrastructure/JdbcAnimalRecommendations.java': 'fe1289db0b4c08f08d5f58362f1fa2bf1b5ce0b087de340987b1ec3efa4195ec',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/infrastructure/PythonAnimalRecommendations.java': '4f064741cf3f7f38797892ec6ec50f330989945fd6a1d904cf036354c36131ac',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/web/AnimalRecommendationController.java': 'b9ce2b1104993d69228aa318cc1655f80d37d8bf8bbce5ef1c39898ae32299f2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/web/AnimalRecommendationExceptionHandler.java': '891dfe346ce2f837f821e3efdbf84826f6c3082ac5eede0d82691a671085daae',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/recommendation/web/AnimalRecommendationResponse.java': 'c4b8f33e660c4c747e707f22bc4d8c6a6d481f4cf86e4ef16029508de635e1cb',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/discovery/application/IntakeDateRangeTest.java': 'd40955e673a843e6106b0fa90356587bb327911f67b4f60ab0afcd067ca30905',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/discovery/infrastructure/JdbcAnimalDiscoveryMappingTest.java': '8f70fed97f7c635d0e73549097a38b22a99b9d81141755ce92e51bccf2d77e5d',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/discovery/infrastructure/JdbcAnimalDiscoverySqlTest.java': '6661c8caa731a51266b5187ba2a8bb69ada3ffce36a91bb2c1cbbec5dfa60408',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/discovery/infrastructure/JdbcAnimalDiscoveryValidationTest.java': '39f9d70a8a7d93386fc0b07f55e8cd96775cb766056b1d625e019883a9f184a7',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/discovery/web/AnimalDiscoveryControllerTest.java': 'b0edbc16789a5dd14388c746b15f01297cedd4e1244f3ff5d517db1feb33ca5f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/recommendation/infrastructure/JdbcAnimalRecommendationsTest.java': '52f9c6246ef36cae8ac0da814661783fe39c556a693ad6a50057c8799ff7feaa',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/recommendation/infrastructure/PythonAnimalRecommendationsTest.java': '58cf9acee4eabefe78b0443c9fbcbd3f959d9282a1ea4e36e81dbf4b90c2dec9',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/recommendation/web/AnimalRecommendationControllerTest.java': '4902a59b66e155d03e6bb5179eb37fa68b5d58f6d82909401218fb02a37c1c7e',
 'backend-app/gradle/architecture.gradle': 'ca825f609fd21787986bdbc0e52668fcb73fa29b180796830c583c601c6b2e5d',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/BusinessBuildFixture.java': 'ec8457df5ae2a9f0c75ea3f729ad19c90041d1b20cb3bcfa0d51365ef6045a23',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/GradleArchitectureTest.java': '10be33ae04d89db2c79731780a42624beec5d1460b1bc04e13c7d9b3749122b4',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/BusinessPackageArchitectureTest.java': 'fb01c5732e6df20e037a02895b752aa5bf354eac5e57659ac9dd015da61c9dc6',
 'backend-app/settings.gradle': '0fbbfd2e3fdcb234148d42964ba69ff5b410b206ceb339ae79a03203e276de7e',
 'backend-app/animal/build.gradle': '2ff3037bc77f583f34c474a3213fb41e2d8242b63d75df85d191000dce4a71db',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalDiscoveryConfiguration.java': 'fd01efd3019bfa7e6d31d5e1850ca8bcd6c5a85f4350b5bc4d91e39071994238',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalRecommendationsConfiguration.java': '545aa79195b7608755f84bf0ee98b81e57b29b1d15080421a7e8264b4ba07f13',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalReadConfigurationTest.java': '85c5c26176aaa1f5ab16426208c135e0d9c8cb43a4167535ab2ef3d3ff1f0975',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalReadHttpTest.java': '197607e7f46cfa9191bc8f237136633601ff131a0a095b2d8b3c4ae99826ff06',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalReadDisabledHttpTest.java': '1a6382f67ef7890007c161fb3813059fd933dae74fe3f131fff9ac0d4343663a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreCatalogConfiguration.java': 'ae24f5d7021e15f979155008819486321b5c4bac1b4615290ea916952a654df4',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreCatalogConfigurationTest.java': '3d73e171e7107277299d3b6d6f1dc64617f2bc5f476e17a48cddf59d57a12e15',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreCatalogHttpTest.java': '344c86c625618f1157e77c90d62d369ed2eae63e477a455acd9286159e1eec72',
 'backend-app/store/build.gradle': '2f473c585123588bf9e9e68ddf52a66663352470a8d3626ff8cef0c2c7be1eed',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/application/CatalogSearchUnavailableException.java': '26899ea435a11dc2664e3649d571eb9b604f816bd0b40dc80ac20644a87c0779',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/application/StoreCatalog.java': '33e43d7f72e925ce390ba0e14f912f06fa1ad9821b81bb7cb0e3d730cd2dafa3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/infrastructure/JdbcStoreCatalog.java': 'b1f4b8432f5726aff03142a0b7d9fb5408fa4ad52622bbcbf532715a74c6aefe',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/infrastructure/KoreanSearchTerms.java': '30be675753fd92d7c21300378efcdde0269fb60e1cf6f46d500ae7efcef9ccea',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/web/StoreCatalogController.java': '6c026fd58da1ae74d5033d7c1dba427655a24f33dfb5baab5a794985b77e840c',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalog/web/StoreCatalogExceptionHandler.java': '533eaa12dce1a6a62063c98f294230a91608c5a519d79e4fcdd0b46c24c09e5c',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalog/infrastructure/JdbcStoreCatalogTest.java': '1b6935eb5d0fe7f87c5c657b671d78e62e4b6424cc81442719c14fb64ffa9e66',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalog/web/StoreCatalogControllerTest.java': 'abd1f216860fa1096eac6b6232b6a72763490c8cd25c4508e4c64c8df15727f3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StoreMemberReadUnavailableException.java': '55cb7c593d5fead8e2eb939ba7a5f2dc6242129fa4a3e7aa81e61972166538ec',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StoreMemberReads.java': '9a1dcdccd27da60b53cdc8305bb4ad95e95943748c77d8ff8952a4eca8d5093e',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/application/MemberReadPages.java': 'f01df9a0e770f47441d7a7e9a55716430af5df8fef88edf9a3fad0526244728e',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/JdbcStoreMemberReads.java': '0872fae34d8a24216c81d280dbdc4995b9771613a83eb57d28eb0e65473744f1',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/memberread/application/MemberReadPagesTest.java': 'a55b5910e156fb6e1732dd651948cd36cf36f3c3ad3215dff9972ea6fc59f0c7',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/memberread/infrastructure/JdbcStoreMemberReadsTest.java': '52e2cae6292bc81d55b6220030cae37fe7a6e4b92589735d128e6ac0a5c9bc19',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storemypage/infrastructure/ModuleStoreMyPage.java': 'fcdd495ef9054ca8d1dcb9062ac70b368626e0a0597ef96c5c2f0bd0556b94f4',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storemypage/infrastructure/ModuleStoreMyPageTest.java': '28fe10101f82f6804d6b7006bac414fb8d0a79982b319982f01030a23a3b65d4',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/application/ShelterNotFoundException.java': '036d1a8090df3017bb55ba1c6c63a3307650a7bbedbe7f4eb5899d4755e7ee4d',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/application/ShelterReads.java': '65bd3d77e56c8c756077f8f71ff785a0e2b5f4f4a037db008f4c7d00ae4a5b25',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/JdbcShelterReads.java': 'b60894a874fd67397059ab221e176d4502e4ba9f4a40df8a70676d2632ed5901',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/web/ShelterDiscoveryReadController.java': '00b577fec69241066a330a929018af470f7acc561bbbb733b31ca6e9c428e2cc',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/web/ShelterReadController.java': '89c6aa2386b7e769134c7c1c2180ceb06d93391db1b29e31b114ab11f5998408',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/web/ShelterReadExceptionHandler.java': '7d1e20bf10aa2c164448376622545a5f45679311f8cae7e42716bfd9787acf04',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/web/ShelterResponses.java': 'b2580738d789f441f1953ee6597e377603ae79db8785dbd29d8c684534b86bbb',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/statistics/application/AnimalStatistics.java': '077a5329875235f4b2ccb6f39fd59b9929112554216924a9a428cd45c0a80389',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/statistics/infrastructure/JdbcAnimalStatistics.java': '59e4bbee8fcc52ba6445c8817f9905b7bedd05bf3f25fe2d81aeb8b8014b11cc',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/statistics/web/AdminAnimalStatisticsController.java': '1dee43bec98bb7d2cee835efb22e66e25bb4d9c7a9266067475e98c5ca00cdb2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/statistics/web/AnimalStatisticsController.java': '271e35c08ece488f9635068341bd87e89b3ed160f15b10b0bc6707f17db8c257',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/statistics/web/AnimalStatisticsExceptionHandler.java': 'b7a71949dc06b2007377a594fb83486e18346bbc6e24b519a0ac55d4bb044d6d',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/JdbcShelterDiscoveryReadTest.java': '4706f37251ed5cd7b2ec89dd61be73ce1f88410e7b80016dca4fbeeb0a14046a',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/JdbcShelterReadsTest.java': 'fee69141e72bb8351e53ad4a4e8660260ad19c7c79063e5e188480d57cbab731',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/web/ShelterReadControllerTest.java': 'c10c0360ed0fa2684712f7ffedda99ef1643fdc342ca05897d7671cc0db5719b',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/statistics/infrastructure/JdbcAnimalStatisticsTest.java': '03fb9ec095e0fc54264cd62c694ddc45a31fb8e8838d524a57fa196332b1d27f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/statistics/web/AnimalStatisticsControllerTest.java': 'ef81e4ce5d5c64253cc54c29f71d643482bb0bddf7957f58c881e70dcf15c6f1',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalShelterConfiguration.java': 'df752f94b0e9d900c1fe79c953ebe0ab9e40b0a5d4a688e0cb493649ab1f10ac',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalStatisticsConfiguration.java': '3f612e87a4b8b26b82dd9b5081e66039e43159017a6f47ebe45eaf0d62c8aee6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalShelterConfigurationTest.java': '69e97cd4214d496b3b68524869ead8f764b79f801be4f1907bc5caf9e7e7d2a1',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalShelterDisabledHttpTest.java': 'a0377c1d7949f65ed251e352c76fd6b2ae6a6815438720082295b4a3fcb39add',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalShelterHttpTest.java': 'ec3b360a65dee92137c0be6ec56b931a5313d2c6960112aa55332fa0d14d88d8',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalShelterPostgresqlHttpTest.java': '284416728e49dcf80507713058f10a7752b301605d0af3d59a3cf17cf0b1e99c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalStatisticsConfigurationTest.java': 'ecb4832397769f222445d5943973b3ee0505a466b76b07573d75fb2adfed0d1e',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalStatisticsDisabledHttpTest.java': '2083c2ff5b8a32dbd9caf4612d92b2f07df64b361c86f79690006cf3c28ab0e5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalStatisticsHttpTest.java': '995b3550ad0098c0f5a0ed5b1b0d300319d3743e4de4cb92214492a4c873b484',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreWishlistConfiguration.java': '57baa20732f7b8f2108739a1f5ec452d2fd889c06685a4391af14ef29d422abf',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreWishlistConfigurationTest.java': 'b41a4d62e514cb34b7d0c66863369e8c4199be4f15c5b9ad3c675fafa2e8e331',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreWishlistHttpTest.java': '39209baedf3d237f174747f60db7581a39df38900af388ca2c343e75ca4aaee9',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/wishlist/application/WishlistCommands.java': '4b890ebbf9ee4bc347940e826d2109bfd61f47765fefcc5d79f6af8c3d5e6115',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/wishlist/infrastructure/JdbcWishlistCommands.java': '25b019ad3c8fb86c3d4237fa2f1fcde9625d3776208383924648ce796a645069',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/wishlist/web/WishlistCommandController.java': 'bb337009a8549ccbff2ceda8e6895f8b82aa78fd3d5fb4b14f98c03cdf849eef',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/wishlist/web/WishlistCommandExceptionHandler.java': '054af4f8f5d5c3f14d04d010098bacdcdbef777b1228f6fe32daab3fa734f7c1',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/wishlist/infrastructure/JdbcWishlistCommandsTest.java': 'a8bd6fe721330fea108ae0f3affc9fc3c54e792963b8c1f20422d3cc9513e507',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/wishlist/web/WishlistCommandControllerTest.java': '41022efc2731cbd3c71a4c21a2c2b20eddd63fb9575834dbefdb0a6d88e75612',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalShelterReadUnavailableException.java': 'c143a24adbafa0e47155691b355db607855c0336237c2ab5383afaf00322d30e',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalShelterReads.java': '4e9920f2b17190a95e706ee6a76d3f73f33cfae3c7da7dd6acb435d363c6ec90',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/ModuleAnimalShelterReads.java': 'aeeb7e6de6f95725bd65d6b19bcd2d75cc70170e5d8a14cca5ad4ba1ce8d7122',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/ModuleAnimalShelterReadsTest.java': '460764be21b4c846a2dc46b5ae3dbb40d5eea96c4eeea5e5fa27bebd3ddfe95b',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalModuleReadConfiguration.java': '586d43e286e8b1822169a1cfaccf757fb460532f3c516df235672dec5ba0b3b5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterModuleConfigurationTest.java': 'a4861bcde1aa67aa60a94447a9a5adee224aedbb47a9cd7aecbb50a60e8dffa5',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/infrastructure/ModuleShelterLookup.java': 'ad8beab20388ea5088349ab318208278d0ed79c27dbc345ae1dff4ba23e46ef8',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/shelter/infrastructure/ModuleShelterLookupTest.java': '2a9da4ad80b0cef65b29d0f6515d853a0920ec1967d8b8e5cbaeb38db6d48272',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/UserMaintenanceConfiguration.java': 'b2c7743701c03ac1efaa6198ba5d80884d990270b156e074ce0c0847bbac75fe',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserMaintenanceConfigurationTest.java': 'd471aa9e932e0b3173fb16d5db291603caf33879e32b19349253d460fce91a98',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/api/UserEventRetention.java': '667c398af2c4b39711d5ac4fa28ff85cd7ef51049bc84acb657d31871cb88866',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/maintenance/infrastructure/JdbcUserEventRetention.java': 'd85e555e018d26203020e63110c0e051f7bea554a3124eceb502f77d42c614e8',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/maintenance/infrastructure/JdbcUserEventRetentionTest.java': 'eb5a1c56cfe6bac640f026b67cc9b2cd21cc15ea9a432b19ce187b7d5aacc34a',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/memberread/infrastructure/JdbcStoreMemberReadSortAliasTest.java': '1d79d8f764301f0325cad326cfe442347bddc17837a799f2f519128d0d71ba7d',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/wishlist/infrastructure/JdbcWishlistIdentityTransactionTest.java': '4162dcd07ed0e85db0aa430257b0a97689887ca731d6617588ba6a80f3a62d86',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalMemberReadUnavailableException.java': '3124e96fe4aa929843c25214bf9a2e00a7bea3bd517e77bb710163aed06a2df2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalMemberReads.java': '4fe1c57138f0b7d68b7a31fa58ef39d3dd5e32ae06ed08edf71ee40fee79e529',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/memberread/infrastructure/JdbcAnimalMemberReads.java': '68667c45088905b5074ba5f4a79767e73e4f4120ad616ea37a15d5cc8316e18c',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/memberread/infrastructure/JdbcAnimalMemberReadsTest.java': 'ca01b5922b6db4a7eee8843d8d17c41e4f0a02acde8b356fb3f02bebc7bb07f1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/ModuleUserAnimalLookup.java': '90a69d8dca1725cd4bec5c3f889f8883c0e552e165e2a3c3a675fb126ed9b323',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/animalactivity/infrastructure/ModuleUserAnimalLookupTest.java': '1204784ecae3bdca29b89ddc40c7d93939e962825a849262167f71de2fedef5a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/UserAnimalModuleConfigurationTest.java': 'cf481b15448f41f7a91a2eeb9311833f0ef426eb8cfaf89bac47cdd740e0617f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/OwnedPostgresqlAnimalMemberReadTest.java': 'feee411b303b258fadbf966c8b0191f13795ad7354ef5854b0e0eab88a616da7',
 'backend-app/boot/src/test/resources/owned-postgresql/animal-member-owned-schema.sql': 'cf6bac5d73bedade2db4325ca1d39ff079138fed35bdd0233ca2ec8d32b0e54b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalFavoriteEvents.java': '01f123117c8dbd87f39c945638200692a166555d525f34d9b4e2d91424e173c3',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/favorite/infrastructure/JdbcAnimalFavoriteEvents.java': '714a2c053576892cd87d9bee25c4cb97f7e2968e7da5fbc9f3e75b5fc7559e91',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/favorite/infrastructure/JdbcAnimalFavoriteEventsTest.java': 'ab2302101929160268c440a1808564a1df7aa8984dc139f2f893dfb4750be1a5',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/favoritedelivery/infrastructure/JdbcFavoriteEventDelivery.java': '8017166ee0a6ac30be828fcdc5566439ab4fc61f2606f0fef5defcbcbb395e9d',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/favoritedelivery/infrastructure/JdbcFavoriteEventDeliveryTest.java': '34994484a77c6b93fa4c9f495c5d8033c2f31c0a207c9edc8927e8b0c61d7505',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/FavoriteDeliveryConfiguration.java': 'fee93294838103ae242ac25c11f564388fcbe74ddf452e19367ba1f1b346da32',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/FavoriteDeliveryConfigurationTest.java': '853eb82cfc8a9f82ae85bfaa4669e6c02683f63528b5c412d2d16150069623eb',
 'backend-app/boot/src/test/java/com/pawbridge/backend/OwnedPostgresqlFavoriteDeliveryTest.java': '90665a876b0e0bd0a1f903796deb4aff9c273d9b260225bff3cd7375812cf124',
 'backend-app/boot/src/test/resources/owned-postgresql/favorite-delivery-owned-schema.sql': 'c25986854e1959de0b6b081dfa23b7b095dbf3ebce074d8281852d63bba84c66',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreMemberJpaConfiguration.java': 'dc9f3ea13f390241c1583e5d356bdf8bc927d0201796c49d2f93503a482bfe01',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlStoreMemberJpaTest.java': '850044232d88bb4a4cd4f9ab8ba002e23842db94d7c536bb37fa01f5a8bee0a1',
 'backend-app/boot/src/test/resources/owned-postgresql/store-member-jpa-owned-schema.sql': '143f6bc69b668d8544995be644980d0547a70e8272445ad43708484cc3dd1bef',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/JpaStoreMemberReads.java': '0f902611e840aea348a1880f41ab7879b7d3178aa3395ae3b89da71e6ca4c126',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/BaseEntity.java': '72a5189bbe1d23106b17934fcd848850b04015b1efb07528e34bea8ba005b0c8',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/Category.java': '1e588433b6ad4d83ea268269cb855f371db0259dfeec341693724aeb32c92077',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/DeliveryStatus.java': '526bd9758054bcd0f74abccce9a3c42393832012942a81449a75e820081aacdc',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/OptionGroup.java': 'ebf039bc56f7762935d4739aa787da45d14ed640448635aebb714c5a97f1ecfd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/OptionValue.java': 'adc297941c1ba8f07565b2562e6e9f18ae7ddfb5623337ceada09fe7b7db3e87',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/Order.java': '5ab90e955c6abac5ab08c960f1c3a48c0d02849a452c4e1d47f6eddd95f1bf5b',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/OrderItem.java': 'b4e0c514c41c8ac46c72fe40c7bca5bd31b4ed2076670057d5a479e6ba8f8a97',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/OrderStatus.java': '58b35bff13fd0e5b478212cc4ab515a61498e8280998d065f41f57a437b878cd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/Product.java': '568ab1576c8c460231215b9df5c72f18fda230fe0e3b79f7974a2a85ebedbe2c',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/ProductSKU.java': '5e194c9c760cd4ba1e2ae1a255a1ab9e420b6bb130cb045e6ab8e7b5e246441d',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/ProductStatus.java': 'f1d375ed14cc30a911aad7c5b8f70141fd13fe05d063f9693bb232d3d56706c1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/SKUValue.java': '07af1b32b497ab8db75b02f313aec78aab9d8133fcdd8d34449fdec4cbb494c2',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/model/Wishlist.java': 'de73eff670755d48ad6761983f60d4cf39588e1cced672af8511e14f1513c703',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/projection/OrderResponse.java': 'b3b95e8cb69f38c81fa41b73fd93955851be9d7d5aa43c10f0af774de0267a2d',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/projection/WishlistResponse.java': 'ed32a1c39754f24a69e2c07cbd4f535f85787cd7d9575878460b14f65cb1c614',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/repository/MyOrderRepository.java': 'b626d77133fef223d1ac6b981e3c058e0d4cd0c821b1851f6f1e8fcdee205fc6',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/memberread/infrastructure/jpa/repository/MyWishlistRepository.java': '1a52a742fffbd8643b317b685c996cc0d8e075ed9acda51961a3b6f31a5b435b',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/memberread/infrastructure/JpaStoreMemberReadsTest.java': 'f816544526e899edf6a6530222d4d317f00b300bfb2e0057bcc94262f84aec3e',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalCommandActors.java': '7fb8e60748731f96dafb414340c5598e10abd8d90671cc408d85b02e8a5c9514',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/dto/request/CreateAnimalRequest.java': '78a489a03560f4026b0379477f474efa4b6cb5a278ac19cb5f61d29dc15dfcf8',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/dto/request/UpdateAnimalDescriptionRequest.java': '1f560cf471942814c6c08e1e561b60171bc093558589a384d6d4e2c8fe8a9419',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/dto/request/UpdateAnimalStatusRequest.java': 'e32ccee292657d479e758d038c977162edd4d7cc0371a87c35f98469fff0ff5c',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/dto/response/AnimalDetailResponse.java': '232864593a53187824cb2a7454b5943c334d7492895f1ba3bc8aa071e5798647',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/entity/Animal.java': 'fe392f325b5dabf74c485b0ffe1fe369935ee1d72d426ee088dff5f2395f7e0c',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/entity/BaseTimeEntity.java': '70ed76034b13302b2a1f57b277cee5c971dc7ab0982a7506679d9208bfe43cff',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/entity/OutboxEvent.java': '234aac480c99b8982aca31b78a940e78f3de41e21c8718e6a325cc1cff6b079f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/entity/Shelter.java': '9350a8214505367652afbba95c4cf044f6582c73ce2c9431f49ddc0996ebedf6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/enums/AnimalStatus.java': 'f41f45c93190c619c2a380ab9dd89313250687a966f9361cce95de65833a6bf2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/enums/ApiSource.java': 'e8bb83137c45e0163c2afc9ce794397c3a8674ef5afa7daf2f73f8f1f01ea6a3',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/enums/Gender.java': '682482f98442032991f7132d67434941661e388520105753a558763e2fbc272e',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/enums/NeuterStatus.java': '42346ea60395fd4cedcecd87b65503998c63c06895f65784751c33e5fae01655',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/enums/Species.java': '440e6d63290027ec6fa8ee09a0b8b1a0e001ef0546394aae09be13f387426b5b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/event/AnimalCreatedEvent.java': '182158001a6e08b8e2d5df87cef7f8149c8396a52311dfd028791380f5c83f45',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/event/AnimalStatusChangedEvent.java': '5bb79d0ccce7a474dd8c15eb7af8aa52ed1add4ae8c9560aa5c8245a30bebc26',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/event/AnimalUpdatedEvent.java': '00275fc2106fe766e5da68f9e2d39927116880c82747ed0c20c9ccce3e242a87',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/mapper/AnimalMapper.java': '2346eefc743944acc3113bb45778cb8aa9c3ca2be2a9788af1b798323efc252b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/persistence/PostgresqlRollbackCharsetViolation.java': 'a2b0c4fd1e3658de7f3f74524ac7006f8c4b4cb39f325b7f20551b3a21bd35cb',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/repository/AnimalRepository.java': 'f1cc9ad378805574e15bb7e181730cbf40ae8e31151390ca34fd0476546205e1',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/repository/OutboxEventRepository.java': 'bed5a9a73adc95d3902a4cf47b2d850d59a5704cef90d0bea124277d200d8a1f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/repository/ShelterRepository.java': 'dec5d389949b4508b06cad15cec580d122752f73633a9404bc654d955d27911f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/search/SearchDocumentWriter.java': 'a0e5bb1e5487823eb3ceba8bc003b0bd32f8a2c8e1483043a5bf56e929b6c1e3',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/service/AnimalCommandForbiddenException.java': '51da7067500e716f534de429c3740a89bf92a465d75167a15cc8e87e69edada2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/service/AnimalCommandService.java': '1952a68977b102a4f5614d11522c5d5c66d1ed9c17ba0fd1519765c837fa7367',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/service/NoticeNumberGenerator.java': '55d8c011350fcafd13c892c460c6cd1327189c652672cbecef04a521c9006110',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/service/OutboxService.java': '163003d80a56cd109d234ede52b486580268404e1fa75e79ad4582d99b513ee4',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/web/AnimalCommandController.java': 'cb64858d80b8ecd7c7831e758bee1ace1f3fc4dc98c5b357405bd4a32318ef53',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/web/AnimalCommandExceptionHandler.java': '1ba1f1a0f579e9904c0736af499445612843a4808c8e2dbede6da14b1372052c',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/command/infrastructure/search/SearchDocumentWriterTest.java': '8fc5e91bf94eb4572c74c7fa7fb48f44447d5f03c7c2694e636889b625e30131',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/command/infrastructure/service/AnimalCommandServiceTest.java': '5fe59bee78d4a52516c53e1b42bcea64dd1d33fab2186a788485b4a89f15e7c3',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/command/web/AnimalCommandExceptionHandlerTest.java': '74fb1982ecae92d07b336e91df77bae3172e2b69702523c7041196e4a9576b59',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalCommandConfiguration.java': 'c41fb223ddb0bda7098f86d272b38f7114f87687f1a8ec10e634b3c3cc383f84',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalSearchConfiguration.java': 'b26bd34a7ecb91682415082b82d2a73cbe6e84eb781009363e35bf9c21921b23',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalCommandConfigurationTest.java': '61c8decf949d90baa7efb75461402f8587fd2695c19cb9567a8a0784b18cc570',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalCommandDisabledHttpTest.java': 'fd46357abd4644f764282f59306279f9902db36d94016aac5cf417c62b99ffbb',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalCommandTest.java': 'd029ee360f565a8b5ca830897a4d5090e78b50e18b99434eaacd95ae55d607fc',
 'backend-app/boot/src/test/resources/owned-postgresql/animal-command-owned-schema.sql': '2446bce3ebe26c621babb14915753b0bc2f70a7a20cc8d64f5f5c319ad52241c',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/animalcommand/infrastructure/JdbcAnimalCommandActors.java': '75b6904f33c30e1707bec6e0b3b3400ca43d1e0ca4dbf8498f987c37a7f1e26e',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/animalcommand/infrastructure/JdbcAnimalCommandActorsTest.java': '6983f5c7402bb915fbfe0f9a8de0de088441a9555a3f29f5c52c2f9233394d31',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/JpaAuditingConfiguration.java': 'fc010dea3dfdec2958847d50dcd059e9a7cf9c205c9718bd212e7a8789a44e74',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreDirectOrderConfiguration.java': '2d94c0628584e50d42a3b6cf4581a5d681c19060d003525e0909d3fbf2644f97',
 'backend-app/boot/src/test/java/com/pawbridge/backend/DirectOrderDisabledHttpTest.java': '95ac01eee141c38edf21718be31fdfd192e5549985d95adddd2f85f1991a4f2c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlDirectOrderTest.java': '73eb1570bf8885f6f942cf271bcdf48ab7cffdaa6e31dbf0a42464a9d16d4396',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StoreDirectOrderConfigurationTest.java': '7fd9b8965f7f520829e520572e66a0f80618eebd0c857ddd5491d36339fb6e30',
 'backend-app/boot/src/test/resources/owned-postgresql/direct-order-owned-schema.sql': 'f10c5d062a7ba47e328a56c137931121f161f5aca87849cc47d25a76921dfe54',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StoreOrderActors.java': '6521afae6621a612d9be4bebbb6521ad2d25c8891d33235f9f3cdf056fd37d88',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/application/DirectOrders.java': '61e30f08f2daa14c29e008444e563a8c6d1134d0957d7d0cb0df413779a8456e',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/application/OrderActorRejectedException.java': '0fbb677f9dd83c7ec500b529692e9a5b8dff1a7718065a7bcd616bd5fd896caf',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaDirectOrders.java': 'f418cb91d54eb85992537c2e7bddaafaeecf3f8ce8073124861411e0153b3624',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaStockAdjustments.java': '05d06a513f9e09db3f8bb44a30c19ffef510187b9aacb7e3f766000a71113ac8',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/PostgresqlRollbackCharsetViolation.java': '34f4c01fbe9899eff9b2de96572c8e686cede900c1b2c39666561e1a05d059e8',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/PostgresqlSearchDocuments.java': '5f486f39504a05e4a3e4b6ddecd478ecb5fe64a4b4c6a5bceb90b4dad2db897b',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/ProductSnapshots.java': '16518726110e1b7404b086439d3fd128c3aae7f321751f98cdf7ff17dc7901d1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/model/Outbox.java': '6436bd61b3a74cf80b0f546f76a543ea1d8ebf8f780cbf6f51fc70d6e70dcb36',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/projection/OrderItemResponse.java': '31944a4def87c0b71eb484372afa37113cdcc81b399caa1aa3f66252f818a99f',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/projection/OrderResponse.java': '34c3180f18f135f869d5a08b24b8126f39173290a25bd3fac96e0b7b7b21eec3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/projection/ProductEventPayload.java': 'a186558ae5a903d437f9c0a1b39d9a95bf4d9391f8b85b4484fd01c77a958682',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/repository/OrderRepository.java': '908b0c1bee6eab55f7cde79de8f164a1e309bf261238fc5eee42d2dca9e3ead3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/repository/OutboxRepository.java': '882550fbc4a3855e7d2a456e5ebfefaa7183473bb3bec868c1a0e818c12b1d43',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/repository/ProductRepository.java': '1431ec7befe6614eca93f61b4a065ee1256e5e78bf3141c8af4f2d591779f3dd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/jpa/repository/ProductSKURepository.java': '82a3926b055ed61f46e234f9d399225ed03f5c7e69a846d19690d61e8c9a07e3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/DirectOrderController.java': '22f0d49425187cfe65c73729761244f6f1ce0afc584ad0b76b382e7323dcee91',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/DirectOrderCreateRequest.java': 'd67860bd6fe374705959e85cb8b34054807d61971e2e4b20c85cc9810bbb1ded',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/DirectOrderExceptionHandler.java': 'c86df4a46ecd544265cdeb6aa27f6faa4d385454150b0adc02239b692919e24f',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/infrastructure/DirectOrderFixture.java': '2d1b3741875a24cee9eac3dc07dc5c11f7daa14d11675c256f09e4ce431ae36c',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaDirectOrdersTest.java': '2b16eaa49c2fb8ec17063f193695bca383e4c9429ff751cf56fd24f83a79aa9b',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/web/DirectOrderControllerTest.java': '27542cf37682143dfcb0f58dc63805e5cabfc2aff0fe58febffe1994d44f18bb',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storeorder/infrastructure/JdbcStoreOrderActors.java': '342928af323fb3e0e28734b0c2bb2741820001680b9a0ce34f1343fc561fa7bb',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storeorder/infrastructure/JdbcStoreOrderActorsTest.java': '35d09a47c71d78895196696bd60e7a0b69cfd8fb051f0fc09e797406de553d70',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/LostSearchRequest.java': '87f81f5e6784011c86ff0af14ede96ab31d4f0b959a1519db406ba3c58e96894',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/LostSearchResponse.java': 'e915c3fd1a8babad55eaf465b04e0816d44c1890764666a467a762dc8c191d4b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/LostSearchService.java': 'cd38d5d9e786fe15ff92b7c725e821f348cb8e2e2b5a23b050c775d2d056cd29',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/PythonLostSearchClient.java': 'c64bbde41791ee4effef7fa65185d31ecd0b451dbb127ac431fa1b765f479b25',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/PythonLostSearchResponse.java': '46e0659ed0e2720df8b485cb4385284ef55128223484469764f07d12fa7533be',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/lostsearch/web/LostSearchController.java': 'f724648e04584055c67bcefc021aca95888fa59849b6d1895bba076fec8eba95',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/LostSearchServiceTest.java': '0c6a63329991b5f382becbf531a75ff25f82da754db2572b7f9750ccc37383b3',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/lostsearch/infrastructure/PythonLostSearchClientTest.java': 'b88e6a481e12f20a1351af47e45009bb87c9c4ea575603bfa0024d5acee11221',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/lostsearch/web/LostSearchControllerTest.java': '0064a05a0726414104543637fa24478956d18d8424ccae043963c61b5971b16a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalLostSearchConfiguration.java': '4f012d4ba49a7a843b462ff97ac6d560a7a83601f214b26dc47664900380632d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalLostSearchConfigurationTest.java': '0809df20987749a15db02d8b243e3379feeb692fef7c86667f0aeae7b7312a2e',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalLostSearchTest.java': '2dfdfed66ff39b97effa0bc462aa322d1f2a1d8172042627cc4cbb06b94a6258',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/CombinedAnimalStoreConfigurationTest.java': 'ed90d7f6de3bcaca00fb23668dbed1839fe1ec92b5fa7e328edad16c3929885e',
 'api-gateway/src/test/java/com/pawbridge/apigateway/filter/LostSearchLimitsTest.java': '5b10904d36809531798a52072011c8496d9f786ba8cfbd4d621759e74596026d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreCartConfiguration.java': '747c2c8af7d7a1e11368136513e49dc1bc2c23affa789e01e49ce1a875e3a4a3',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreJpaWriterConfiguration.java': 'b466afbadb3c1e93d01d952e113ca39bb3eb299a57c2425101a97b91e9beebbf',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreOrderSupportConfiguration.java': '9b4cf920df15a19307f790fcf7371f364dc6e040b3575f641d18edb9c5f832c4',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreCartDisabledHttpTest.java': '5d554e98e3b92457b45cf713e87a3ec5992c38f9406159c1e983fd34aba6442a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlStoreCartOrderTest.java': '1943ff6e4b7c22d2031f3e5f8f1f543ea0e22566e94beefc015161aee14e92ca',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlStoreCartTest.java': '53c2973a302b47a3a8ed2c9c6d3fe51e7920dda85a48d7346824e75f633e0ed5',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StoreCartConfigurationTest.java': '415bccd04c341baf11b64939bc92904c697e92b58e406d4e62e154e88d484b23',
 'backend-app/boot/src/test/resources/owned-postgresql/cart-order-owned-schema.sql': 'b1a72de7ea05a1dac7514d4fc3c5306057716b8082fd6eff635afe7fa96f7b4f',
 'backend-app/boot/src/test/resources/owned-postgresql/cart-owned-schema.sql': '801dda5e91c393e2a9a227bce02ea82b902eb8acbe11db0791394ebacb10b690',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/application/CartActorRejectedException.java': 'f7d9f21251010d404cde22631147fe3df4ee762e202a58135c800095cd4eaea8',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/application/Carts.java': 'f519e9b0d33534017515ec58d44db961e9717f4507c37e503e399f2518602401',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/infrastructure/JpaCarts.java': '50501fde4e7758b0e319726ed9b839777f5e22ad3aa12ee6f3103180f1f11ee2',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/infrastructure/jpa/model/Cart.java': '77d3f3034feba4091f0568812cf85d1b9282ececbef7c881058018681ddcac57',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/infrastructure/jpa/model/CartItem.java': '5dce217a247611b572daefe201a1ab62a98da2b74351d4af3d2b945daf03ce81',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/infrastructure/jpa/projection/CartItemResponse.java': 'c563e630ec28aaa534bc4a5d3b8b2f9b63ff7d9b22c86bc7353300d97bd216d1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/infrastructure/jpa/repository/CartRepository.java': '3b964bb5c8911c031a19a637f959bc3a4c39491a6bd563900be633ae9088b7b1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/web/CartAddRequest.java': '609203ecbbcf7c99e6830ae0682ac6d1cfdd1bbfe2b377bb646af5d582503d47',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/web/CartController.java': '7a6e9f02cee36ff2f2a0145266d2ae893857a45e2344a9098cb5d4c20e91c5c1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/cart/web/CartExceptionHandler.java': '447de5936275dfb14bbf363037d908c612f3bf1b45f022aad73234110e6f665e',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/application/CartOrders.java': '7d6cbcce636fbdd25c2ce5f82a3dd8815a3bd2fb50cf04eadefb147509eb3379',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaCartOrders.java': 'a5b16fbd15aea59f11eeb5d1c44f0b9c5613dcfd3a146d3c7ab2d6aa7b9d7763',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/CartOrderController.java': '1bff80cb246f3a5b1daaec5ebc835aa14c76239ecf6a9e7fc1e641f3d50b57b9',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/CartOrderCreateRequest.java': 'bee9d607e30758de42c217cf194d8e3db882ba540023eaf301eaebac3d6e9bbe',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/web/OrderReadController.java': 'c59e270ee0c8457878818b070fa5c3ae7877d9f27b5228e0cd42da0947846827',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/cart/infrastructure/JpaCartsTest.java': '0777f816f04d0dc46a99c21cf218692094ca7e2d71b78dcfc63744a986707aea',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/cart/web/CartControllerTest.java': '3ec1a6db8bd2ffbe6399e38f11b62b42a639228c3b33734ba76d5733ac5a0a43',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaCartOrdersTest.java': 'ad28384cd03aa638a582027d5aec5f5a9117e501fb454717439353607ff0d064',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/web/CartOrderControllerTest.java': 'd0a5f3b6fae909c1222c9594ea7d36d39f56620194a6345967424b73718e6db7',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreOrderAdministrationConfiguration.java': 'c7182b7c51d1dd57451f0d47d5a145a4c747dbf5a614472ce7553d86181aaa25',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreAdminOrderDisabledHttpTest.java': 'e4e294cb5736b63efdd4d63c013ca7636f33b7e719212eac80e887a96558b49c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlStoreOrderAdministrationTest.java': 'fd4d26f4b1ba66e2ee1f794b74a0c56cdd8e29698e0b060a209c979879166651',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StoreOrderAdministrationConfigurationTest.java': 'f3f8f224cf722567db05597a947647ec94ba9169ca945389a8721e1cf22764e2',
 'backend-app/boot/src/test/resources/owned-postgresql/admin-order-owned-schema.sql': '6e051995638179cecd99e7b206e7668db11d8074efc959e5da5921b13cb029e5',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StoreOrderAdministrators.java': 'bd135cb2d2d5d1d36ad463a5b953dc00ee1e9e20ee0243551474be9d70db1d13',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/application/AdminOrderNotFoundException.java': '399710325d5a708fbea581f11d598736dadbb7e6ca6c8814303cf528092a9e60',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/application/AdminOrders.java': '37db177da84a1184875a43925490efd8dd44fb0df0007aec9e7ef0c4398e5d44',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/application/AdministratorRejectedException.java': '191fc711e621ff98e5793c22aef870dae4473d9a1fe7126144c110910730a897',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/infrastructure/JpaAdminOrders.java': '4e804c318e8eb4f18155b208c4c9564802fe36bd3d60564a2cd6d6cad06f13f3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/web/AdminOrderController.java': '084053c3a45fa0b199fd76d6404f4b3e3cfa92c4b1e7934aef4166493d271065',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/web/AdminOrderExceptionHandler.java': '5f5130f3afa7bde9f80c2fe4a76da5b8af016cbcb0074a5e6cd38a9ee5d7823a',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/orderadministration/web/DeliveryStatusUpdateRequest.java': 'cab44a74e535ba2bfbe145c23778bb611958747607ab1cfa7a32cad35d86dac9',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/orderadministration/infrastructure/JpaAdminOrdersTest.java': '182d6e1a49b45bd8a5d1214261ed72e772ea0fb4d67174dfb253cfc81f2546cc',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/orderadministration/web/AdminOrderControllerTest.java': '4a19de3c8e5edfba0f3fc871045d070f75c89878e7f9f825bb1793e93ef116b1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/storeorder/infrastructure/JdbcStoreOrderAdministrators.java': '8e568e9d31a427c5a6524bf31703d054e0f736b6437549e2fbcf8ec48a404ac9',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/storeorder/infrastructure/JdbcStoreOrderAdministratorsTest.java': 'd40117f3287de017706c86dc7dbe230daf031614547c3ee25e97354eeb7528ca',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/application/PetTravel.java': '1c9d3fc66184ce0943fd693175b86013a41ab71a1fefd5bc7d5b333e6b2ca966',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/application/PetTravelException.java': '57ff2466126f996fff22167e377564685908f08d2888d6d814fb73dd3e06dc2b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/application/PetTravelResponse.java': 'd196762b8eb7bbaab39de007ec6fa6498b7530ec6e3aedc7e8d796a373b983be',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/PostgresqlPetTravel.java': '6bf68d941786e2ace5db44e87e3e69155d29f3b79b472708592036e83646fa42',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/PostgresqlTravelCatalog.java': '09a3672123db9350e52dd6fe7fc14028e76518a7a3febd2a882bd5b0c7b1476f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/web/PetTravelController.java': '48dacc274a040b0467fc6a73878e530b1f0297739bb228d9e73356689916262f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/infrastructure/PetTravelServiceTest.java': 'cbaf9685d7c864dc9a8493c3ac2250eb42535445815378be843ce6f1995e7ece',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/infrastructure/PostgresqlTravelCatalogTest.java': '354752025f93f899d7ed32350d21bb1d07ce125e094b6b699e2898ffd65219aa',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/web/PetTravelControllerTest.java': '87e5f9ce29e1358b184a7d4e8a0d1c7aabfdd49678a91f62d312a41e119dca1d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalTravelConfiguration.java': '3da319abc4d8115a0a8ea31188b4e296c1e82e1332c798e9d4c102d3edf90e5f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalTravelDisabledHttpTest.java': '0a9997ad315c87403e039c3bc4eab82773a9fb9c950b4466b2c3e994aef3b4ca',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalTravelConfigurationTest.java': 'c5e80f77e28babfea3b7ff3c8ec2a0dc86ccc31c6aeb839e92dc887b1414ec2d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalTravelTest.java': 'e737c4b5a00158a337c6ec85ec09ff31b17040d9972c0f784caf3b421b850877',
 'backend-app/boot/src/test/resources/owned-postgresql/travel-owned-schema.sql': '768de7c025a67d0153045e52e95c99576cbbf466cc48c6cc0cb2dba993960001',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/api/AnimalShelterMembers.java': '8439b1722402e9ce6e6b1751dd4659ca48901643ff50c677b13aab3987350459',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/MembershipLockedAnimalShelterReads.java': '1c553288207cc42fe21e5d0eb32c0d1f6e282f2cef96c6fa8a21d2cb554f5b32',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/application/ShelterCommandConflictException.java': '2114af30246cd617f5d02e32b7bb952a80a209f42d88a69ce8faf0c980f36191',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/application/ShelterCommandForbiddenException.java': '968bc2531b5d6c060448769b51f01a73bbb879fd93380a8348a47b9a8a813298',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/application/ShelterCommands.java': '27482e96d484b845fd091456b3198bf83be95efd4690653469944673643574a7',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/infrastructure/JpaShelterCommands.java': 'e8a583292e0fbfed691b2e23e94c86ce33fa2d9b7434a43a0152d5a1a2394902',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/infrastructure/ShelterMapper.java': 'b458c666a882a375e7fedb28c3eb89610bd5f85e513cf901800dd3220b1854ce',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/web/CreateShelterRequest.java': '8fb8d44608eb2558945ceedd2f7730939745368cdf200152cd7bbfff512c54b2',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/web/ShelterCommandController.java': '189762c626c1a6459481f87ed7f8d9251b769bc44ad213c48d2704f9789912c1',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/web/ShelterCommandExceptionHandler.java': '608796ee97f31ec1a0fc047fb343a9e199e44d1b9363754b131b6203cec44ca8',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/sheltercommand/web/UpdateShelterRequest.java': '75bd2aed1a82e840de57c07336efdb9b322fb529be1e3a29eddcef8a50939e4e',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/command/infrastructure/search/ShelterSearchProjectionTest.java': '0052e8ea4b5fde18baf76791864daeb97b67bbe5819fc518c77fa567d01eb7a0',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/MembershipLockedAnimalShelterReadsTest.java': 'faf322dfc87ff4b4367abad540bb0cb400bc292c884c46cfebd2c4b0146ac6eb',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/sheltercommand/infrastructure/JpaShelterCommandsTest.java': '9845d63ec7a0cc2bd9b60a085d8df314d64117877ce372adc21c472af63b1800',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/sheltercommand/web/ShelterCommandControllerTest.java': '8ec26439556edc58e9bccde4569cdecec3b28880e06d61ec56dc6194e15d7c39',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalJpaWriterConfiguration.java': '9a5868d5e31d33cb49bde439c45193b35c6c107e550b4b68e30f75386dc9793e',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ShelterCommandConfiguration.java': 'b4d09a5eb62f8c475c59b1deb0f6defc2614e646dced82c29fbb122c00718459',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterCommandConfigurationTest.java': '287847d03840170b6a112104fb261d2ff34f80237e2de066b4d44c9d0b0b6fa6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterCommandDisabledHttpTest.java': '7ee26d28c0ddee7d92ae89cd6a342eeaf12d5afb7f3396bac7a1836ea0ae5cbf',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlShelterCommandTest.java': '3791697f5afe7d736898c1298cd1b1e968632d4be31be410a335d6c94063faea',
 'backend-app/boot/src/test/resources/owned-postgresql/shelter-command-owned-schema.sql': '3da476fb37895bb86411ed5e08be5dac86b6259d4583a20a19ff8318bc53eae6',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/shelter/infrastructure/JdbcAnimalShelterMembers.java': 'e2606d0c7a9b7a2309323808cabc29ed9fd5cdbfaa1e2d928ea391629ba26adb',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/shelter/infrastructure/JdbcAnimalShelterMembersTest.java': 'f04b7a60172b005432ad30ed50f62b704ffce13becb8f411da13b810950f4561',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/gallery/infrastructure/LostGalleryFeed.java': '823379854be7fe09bf479a7fa749cba1f4e213e0bd28a3c4f213ab673b8b528b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/gallery/infrastructure/LostGallerySnapshots.java': 'e97cc06cddfe21a1e00f6e2e99b45c2c0f0f376882a95fdcdde380b581e3c058',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/gallery/web/LostGalleryFeedController.java': '9dae2332870beda99c1e17b68b12c0a3bf8cecfb72aa8f9993e6c71da2bf43af',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/gallery/web/LostGalleryPagesController.java': 'f9aa5378d32e43b0e44ac740c574ab04c2627363f92af413765cb9f0dc1ed60f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/gallery/infrastructure/LostGalleryFeedTest.java': 'e7b5161d3d9d154b39ab86336bd288e457ee6ffb6b9f6f7a9927e60da2cf40c5',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/gallery/infrastructure/LostGallerySnapshotsTest.java': 'ec23db7f940f18a4f625fa0803b4a6450053d89fe532330dd675a557111a2193',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalGalleryFeedSecurityTest.java': 'bbaf58ce8aec6051f7e7249698e48962525c7e0179128d58fcf1a85373edb911',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalGalleryFeedConfigurationTest.java': 'b92ef46891e63bfd1872a9e96cc38b6eac2e8b0bf8e70f26dd3f58cfb2a37d15',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalGalleryFeedConfiguration.java': 'e2d526b78c540d9cdb16fc4848e539df5f03ed25b18a2bfa5a71a270d3211b7e',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalGalleryFeedTest.java': 'e70e4a22ddd29fc5f294f007c89101f094ac2680eec9e9d6d9581dd01334310c',
 'backend-app/boot/src/test/resources/owned-postgresql/gallery-feed-owned-schema.sql': '01b718b927cf5c4bd9137ea03b386e5fbf3515d3e4c15b9f66ff01a234c71a92',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalExtraDiscoveryTest.java': 'f2ed6e5077880bdc688df2d2d868f4b4e872b9fc008850b89de5a07b64a63c90',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/PetTravelCollector.java': 'f7363de2c48f587bf32d4ec5b5b6dfcfdecc1325ddf51926db87ca5db6f706a5',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/PostgresqlTravelCollectionCatalog.java': 'a5bad3101ac4915e123de4889101a360bae10fa25c1ed233173a36d8c2783f3d',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/PostgresqlTravelSessionLock.java': 'cb6b3251894a4a70257a3d201fafabe065f0e9cbae684b01df7112ce541b8b97',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/TourApiClient.java': '218a0ab380a018e5f1d3fdbc210c7905b652d440bb741267559f5662818fa818',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/TourApiProperties.java': '435ecd60c1ec0abac90accf85228c64ca6a487f445d4a139106bba6c591cf9dd',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/PetTravelBulkCollectorTest.java': '72840ba7945251fce64dff3e947269e009e4b003fe4a49fa148b646e02e84f47',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/TourApiClientTest.java': '1837aa98273c874d4cd5d8c0714070d41f63af8e642408d0897b8f4b08a9e70b',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/travel/infrastructure/collection/TourApiPropertiesTest.java': '4c751e591fa617e661e59a0e148cbfd2661744e437feed7bcfc1eb3a06d4c333',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalTravelCollectionConfiguration.java': 'f48034221ca359fb9d1ab8d953a5ffd3a10268032acb4641a4b7001d9ddf5766',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalTravelCollectionSchedule.java': '799ac9e07bf69fe4e3611d00e586732de175b3a01ec6cdc9088f681ae9396af7',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalTravelCollectionConfigurationTest.java': '295af2cf8ccb4082254e13d87d51841bd28cae556cb4d448a8440b9e6150729c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlTravelCollectionTest.java': '98e29429cd91b6fc66bcaa25297b099d8f75983c455f7707e2817383f3ab753f',
 'backend-app/boot/src/test/resources/owned-postgresql/travel-collection-owned-schema.sql': '6174aaaec39c6920d3f3a30767a6276e3f2f2282095eee66a24869a0601b38c6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsAnimalSnapshot.java': 'fd06d20230bb15f6ff5b510774df77cf79b21ac5d9c141828af1d8fb0103a444',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsBatchExecutionRecovery.java': 'deae3901d41c1ee6ff32a077d069d187c8b546d351e3c208edcb67e9c17679fb',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsBatchProperties.java': 'f2dad6aa04349b2ec17ef5af8fdd83a95157c94f83a4f2b61279697be61c6197',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsBatchRunner.java': '6ab70bec741bab17f853d538e5a973d445990749b6528eb17ecc69147a6a2232',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsCollectionVerification.java': '5508d1c8a0366f0e864b9abc00265a6b0582dcb1e6674f53cdcc822788f828d6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsPage.java': '1b9b06e538e93a35af20d1440201e07491b905628b5e2405866b2ba54654229b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsQueryProgress.java': '69547d373373740f4a81063246d43d5b93dde0805fd02baa187a4035c8a2fb53',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsSyncPlan.java': 'dbbbc14aa3f97e03411477884f5474c10bbf58ba2399ddc5b4bf8444680fae79',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsSyncPlanFactory.java': '55cbafe0dd0d523106fdf1b38728f58bd498663ed4900d7fa69156fe763265bf',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsUpdatedAt.java': '88b2ffd47550866959737a5e6e21340b672a116e08b3310bedbf7f52f93cfdb4',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/PostgresqlApmsSessionLock.java': '043c142f0f3a72b2c24a2297b3d877557390caa806dde68802419fb676022aec',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/client/ApmsApiClient.java': '838fa24a32d6446d9333a8c05662fe55b6791b2b794ed635696fe41e13255a9a',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsAnimal.java': '36506bf8147f972d70937033d0d0ce98bea3ee651c1006080cd96be79a63d5c3',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsBody.java': 'e60d4184647686932677b615c33374aa6017ec55f439ccbba2b4c2c430702aa9',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsHeader.java': '728c45a6b97f3c41cfb04ab6d48fb7dea65cf49d382594fc08accecc89bdc479',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsItems.java': '0438701f9786373c12244954a2fe9acfe60a6427c8215aff3466a4e907b70381',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsResponse.java': 'e72ddbe54bbab190159adca464736112c3ba9a98c1f3f0595ea91ac0ec542a2f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/dto/ApmsRootResponse.java': '58c9610c562e588057ccccdd4a3e25f2c3381d4808f1057be5da233269eddc93',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/processor/AnimalItemProcessor.java': 'b0b799ae953d36357dbe0a7cb86b84570f1c48b3eb8bb1842ce41042288dbb53',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/reader/ApmsItemReader.java': '87274ac8e97cde77a11bb7e458af93e11a2120753590378ac7d9bb744dbca65c',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/tasklet/ShelterPrepTasklet.java': '69737c47a73e26b78ef41c73a48026c28e91f572a42c8c0b604282d84507e037',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/apms/infrastructure/writer/AnimalItemWriter.java': '14df104193bcf184ab7fe5cc09bb1a1d8642ef9a93282093633686b23bdd5249',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsAnimalSnapshotTest.java': '2df6cfef54435f7b03964bedba6ab20d31e2f5d953d6723507e494355df5e177',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsBatchExecutionRecoveryTest.java': 'f73857f04687d59c6d9e0f8af01fe728bc88cff97cfe2dd28ec80505175010d5',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsBatchRunnerTest.java': 'a03d213cf401509699a955a6fa59ad68375c4e1834f81242cfe5c0f22614d204',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsCollectionVerificationTest.java': '6ca77c9a7b48f9313fe03d4dcd645ec0725df8eea1680f262be7ae409e7ea8ae',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsPartialCollectionTest.java': 'c64bad554f9ce08b9a6b15e564f6a264cdcc9571f50c15757eeb621d46e8fbea',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsQueryProgressTest.java': '790e643720de65deae58074014c90cc98cf84ba0da20a32be3528e6d57eab258',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/ApmsSyncPlanFactoryTest.java': 'fac6f8b1a9327ea4fad386889caf7178894a53bb99f28f40e60efafdf4f5caf7',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/processor/AnimalItemProcessorTest.java': '8ce5b60223deb9927f4e74f8a28cb8fb618a6fb17ec9d4fc998045f3b5e88155',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/reader/ApmsItemReaderTest.java': 'be4394b6fbd0b97126bd2e6572b55a89649198654ccd613ab9ce0e31dfc85f24',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/tasklet/ShelterPrepTaskletTest.java': '696526c124a21294b97be7d6f8b651a963aaa5985a7084c8d68b34c3355900e5',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/apms/infrastructure/writer/AnimalItemWriterTest.java': 'b6dc3132aacb50a922b8e5970791982ce166da1a9c76efb3cfbd8b6126957aa3',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalApmsCollectionConfiguration.java': '99b41ec9141e3c81f53bacaaba1ca02077695a8d3f22cfc70506a8ecf592b88a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalApmsJobConfiguration.java': 'fe7692576153f0d62cfc7805846a5380453531a6f6f46a4c4f13f22813196c5b',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ApmsCollectionSchedule.java': 'a3e89832b8d9c364ea7fbde6a6ac67841010aa5ecf38dff1bb4d2a0f07d2f6bb',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ApmsHttpClient.java': '975e28a2e9006b44449cd05fbac62490ec81b9fb3b03129d3e193e39c147242d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ApmsScheduleSettings.java': '1e6a696c57677bf47b99f7227c38f17e0cfb9ae71e53628931be4c62ce28415c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalApmsCollectionConfigurationTest.java': 'e04c774e73f7ddc4e6b029edc1242fd802f5c4051a4a83ebb2957c212379256f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/ApmsCollectionScheduleTest.java': 'e60db77f942730065c604029e82b8761d5f625bfaa2d60618e46a1d3b06041e2',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/ApmsHttpClientTest.java': '39e536740b81281ffe176a1c82a572e382b57e28cf455e7e2aa9bfd8cabde376',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlApmsCollectionTest.java': '88f045f46683765234b1d319b80f235fb5432a4710d32d234937894b1503ac77',
 'backend-app/boot/src/test/resources/owned-postgresql/apms-collection-owned-schema.sql': '55dbd18513400e3037f025f352550c1c10dff2bf2a8b110095f601276e27f8f5',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/ArchivedPhoto.java': '98cfdb484bb9bac7c2fb5377777984531d5d8e8a444737deb29c1891c23e7f2c',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveFailure.java': 'd37822c6579e217d0e1e2eceb0b84a45dcffd09c487ff003b44810662816d8f8',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveHttpClient.java': 'f56e96bd1a9741738baa6cea0ff40339f5967d27c5fbc6e9a06f7a492e4c85b9',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveObjectStorage.java': 'c0f2ad588cdff85ea1d215c50f5947166b42ec96d657b37cd1ba7eabb2ce7862',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveProperties.java': '6a7ba0ec194c8dc6e09e89b77318021e0239aae9133e7c5331235da359771ee3',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveStore.java': '9229e9728611281a4148687e8b0f9b22940979ee8ca7e031e0cd4292076a995a',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveWorker.java': '98b4f93396ed812dcfbcb5b1c9c175fb59cb4ff59ddb2bca8507d628383d6fb1',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveHttpClientTest.java': 'b2bbaa5d3056e0a3bea533a8c4f6df2a2f351868f8259d5c55df41f5309d3e78',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/photoarchive/infrastructure/PhotoArchiveWorkerTest.java': '7609f598e30fb3e84d3635e82c2e29a8e458a8b645c34201a8e7ec02897c51fa',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalPhotoArchiveConfiguration.java': 'fb57597c11709432895fe5de7b1adf97f47051bf42c5b47e888a0f0248ebdccb',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/PhotoArchiveSchedule.java': '3aed8ff6a17f94e60da42f60512705d27e577f760a1b3217aeade357c7caad7e',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/PhotoArchiveStorageProperties.java': 'd123601b7804c9fd1a6784cddfa42e43b2e55db1653260bde9b9016e0f7a7cb8',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalPhotoArchiveConfigurationTest.java': 'b977d0c04d45e263a531161c002b5f22cda2333fefe32330c49462cd6ecf14c4',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlApmsPhotoArchiveTest.java': '37bf258e955fd82b621eeb71fa719cd3e571e99aa654d72ad98f499b2f8e4a41',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/PhotoArchiveScheduleTest.java': '6d0fd9f7595bc940d3a4254ba70ac04c5e712d648f1af6cc0a95661d9a8afaec',
 'backend-app/boot/src/test/resources/owned-postgresql/apms-photo-archive-owned-schema.sql': '6930ff2d0f3bcd0bd48bad24c16410ae3348ed1480c9284037b0318f15c3083e',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/command/infrastructure/search/PostgresqlSearchProjector.java': '53d71489f56bdf98f031e5e0c57e9a0be58705b729e690934521976ffe74950f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/command/infrastructure/search/PostgresqlSearchProjectorTest.java': 'd2134b091fd44fb7e29d46f38c2cd92a73b3a229349a3829045ad6e2203b9258',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalSearchProjectionConfiguration.java': 'b2946d495abc9925ee20a90a587f8e46b58d081bea7e77380081a280ec258181',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalSearchProjectionJobs.java': '0811398961940ef9c0a0337d1a8a9a4f03522c41f25ff7a4d99c5ae40251f17a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalSearchProjectionConfigurationTest.java': 'b73e35bca3c8f3e17a7a06a90f16fdb8b70b8896aafdc5b28b0a581514efa465',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalSearchProjectionJobsTest.java': '9ef879fb4872e5749b2b83f618f3c2ae7abdb679b92a702407d63e37b1710a95',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlAnimalSearchProjectionTest.java': 'cc7bf19e551affdda9f4c2760f859a5a8a5c08c0f13d57fbb0814c9facb3b4cb',
 'backend-app/boot/src/test/resources/owned-postgresql/animal-search-projection-owned-schema.sql': 'be0bd49960e0dd85cf0ef1f25dc77704b5d61563bad9a5c989db7e4930875eae',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreOrderHistoryHttpTest.java': 'c2a3128e22ee8334035e2d0319f987bb8d8c8ac7ad36e017571220f46dea7ee6',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlStoreOrderHistoryTest.java': 'db1585ab2ffca292564f34628ea7fdb8de20938fa05e24f9b499ada279b2d792',
 'backend-app/boot/src/test/resources/owned-postgresql/store-order-history-owned-schema.sql': '8ce980cb07524f7e8129c7f940744ee36dcdce5a3166b4d7c179e8f509b7cdea',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/web/OrderReadControllerTest.java': '370b0795ed9a9d5263041427be3a8338b222f3d29087d01071d8ea14c100a3a0',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PawBridgeCoreIntegrationTest.java': 'ccc18c338701e547875818bb7d151246963ea1d1b1170e48c728239c435746d0',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/client/FeignPythonChatbotClient.java': 'f7dde447789f89f92a269f7d56462f48e9b1a559f13c310c0fd625984e50d116',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/client/PythonChatbotClient.java': '9fb1f2ba878a7b71359eaf1e5ccce3ef32905671aa73ae48722ee7022b6c9b1f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/client/dto/PythonChatbotMessageRequest.java': '780a9c4c7b2cb00f99343c7478d3145eb726a0fa2d8053fc5f5de5d65d5fa1c4',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/client/dto/PythonChatbotMessageResponse.java': '886d4cccf7b85f9e22ec5f000c37981cd40daffae27d53c84b4712007f79e9da',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/dto/AnimalChatbotContext.java': '959fd4fb875ed2f9139a8cd79e865f2926716ceab24edfc9184a86bdeb312fdf',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/dto/ChatbotGuardResult.java': 'cd7a4f8ebbe726de33d4557f1afeefab6b4652f990bc1c2b745d6f01c4c0b942',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/dto/ChatbotMessageRequest.java': 'fd18bfabe1645611c101be401cecb5c1ccc82cc595db0966b8dc9d199cf1a2ab',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/dto/ChatbotMessageResponse.java': 'a4d3798bab5e8efc3d9b374ce242d74c52d3a7408d1fb57a591d26184973b486',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/dto/ChatbotRecentMessage.java': '764ad8f0bd061b2fb3d8d24ec3689832d8a080ffcf96d659f0c730f278a4d020',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/entity/ChatbotBlockLog.java': '233d465b5d4665b6602fa67b4f14a11a7a4c63eb50dc5d38bbaac86e416af6cd',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/entity/ChatbotMessage.java': '075b8e95c324a938469ef5e3a34ba1ac5f60f8f5ec6242f4f48290da5970e279',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/entity/ChatbotMessageRole.java': '8d979ab86bd4036cac8aa0efb72e07abaa9005c93814a709f97fc71b2bb30b91',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/entity/ChatbotSession.java': 'aa092fc6e122e9b436cb433c08335c076860419b074c1650b1b0e36b5ecde707',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/exception/ChatbotAnimalNotFoundException.java': '6d81f5aff10056d13a1a8d511a5b74354f9cea75339f5eea2181f85f11f97189',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/exception/ChatbotRateLimitExceededException.java': 'a2bd5fc2151b6763262ae632c3817cfbfe9ac29bbeb198b761b1cca821fb2e6e',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/repository/ChatbotBlockLogRepository.java': '1141b45ebbe250c69a41474cd56b23ed8f4e3d13705691ec77398d567d21916a',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/repository/ChatbotMessageRepository.java': '48fb338a9ed0537b435ca9a73900cf237b0f279e60e7d8d294b79282c8b377c6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/repository/ChatbotSessionRepository.java': '9a9e1d0f2f55c1f45bd4d2962e44d8967e91b1ab8ed664e3fbe11dfee83a8490',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotClientIpResolver.java': '12fa15e7e5af691464ef3cfccd61c6a31abb2894c744e625fc91f00da009e954',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotCookieService.java': '503116d846dae5b4a666f15cda30c35e33a247a3b8b26d4a9688446f4abfe9cd',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotGuardService.java': '3ed6c5100574ada9fe717ececbb26cb1c712df6dd5a7e0b533c1c0d1c1f32f8b',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotIpHashService.java': 'bf82b2fc9fc840fa5a594ec0acfaf88887c9e481fa8a43aae5904d01b6341651',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotRateLimitService.java': '8c212e55393c36bdb21cb61fd486ecced37a49ec464352d7df87ae2e5cdc936a',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotService.java': 'aa8ee37a626b1d06eb92f4507456fba1c9e7110201b54297a12211df75d114e6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/web/ChatbotController.java': '743e0340626a3e8b7e5c87d1210b0bc59887aad498f0ccf5e038b5f0213ead75',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/chatbot/web/ChatbotExceptionHandler.java': 'c83fd7e96e2b8b68d15ccefb4597da0b458400b6e26eef9b80499c5f2307d6fe',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/JdbcShelterObservationRecorder.java': '3e2dedb60dab6d6a3da25403e581a59b9c47f037cfa13fb90235eb99827fbe74',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/client/FeignPythonChatbotClientTest.java': '28de817ad8ecff333471e234c01dd258f902c05ff4caaa8dac28519a40585465',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotAnonymousIdentityTest.java': '0fff9f3d9d56b03846871eae3e2923904e256c24acce30dbd888ad2629398c13',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotGuardServiceTest.java': 'e0251a8def10045fcac3f5cd01306e1aa3d0400b4db09ce6e1b89154c8064d13',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotRateLimitServiceTest.java': 'a03253cd871537258a2e94e0c30408328130ab422fcbb5816817d660055048bb',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/infrastructure/service/ChatbotServiceTest.java': '069d8bb3b5a28b0d3f9d93f83f33d2852b418e8a8abf0391784f8569ace48007',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/chatbot/web/ChatbotControllerTest.java': '73e6a2c712e318b053b75a70fc2913f493d63345a7d505fa2cbd9a1c37e43f2f',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelter/infrastructure/JdbcShelterObservationRecorderTest.java': '5f4fbc0e9eaf402e5f71cf5b3899672bdf160ebc07c938e02b20b9ad6db0d835',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/AnimalChatbotConfiguration.java': 'da15eb25c94ee38f3318524a95d3658455f54e78fdac693a6d946bcff226532d',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ShelterObservationConfiguration.java': 'e642f8a8e5be552eb652dd102244516dfb84de9f74d51ead49d54ec932c4418c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/AnimalChatbotHttpTest.java': '9a5390ef63697cae51ebe3fbdde953856a9e3359e2ca9d62d3b30c0f75502d6d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/ShelterObservationConfigurationTest.java': '6ebb20bf06c043e28c876cc54d93e490d5ec8238f02afbe479b0791a31acab0f',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/AnimalChatbotConfigurationTest.java': '2afff33541134c3eb21122d390296a9ea4809185fa0dc300d8a613073f91e11e',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreCatalogManagementConfiguration.java': '7bf82e0dff3bbd4aa65e1664664aff1af2bf32ad4ff7f820093801b507bee438',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StoreCatalogManagementConfigurationTest.java': 'b7732a2e4e078bf59cb8521876dd9ce95cbfc4481f6ce03a8cb26fdd19c56a7d',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogOptions.java': '94233b281e8d40d00de6b64adcc044071bcfc2a565a3c1b839fcb66d9bd761ee',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogProducts.java': '759594b3e1db33f4f953590abedce9059380aa5d6a081aa919faea9a6afb62a2',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/ProductSkus.java': '1f8b7e8e17373b10089eee51bd2897f2ff8f0ca8002fe106caf168a0ab511e01',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/S3ProductImages.java': '3213550d54dffe175ec4d26440c96eef81cc56ef78c7e4aececc53802de8e84a',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/jpa/repository/CartItemRepository.java': 'b7934c8c59fe3ac15834a1660b80d5f5d62107243d2987325d51ac6e3dbf5226',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/jpa/repository/CategoryRepository.java': '8c6127932e33b10b7967df22844587a2708e8989beccc513c3a2f335c32776ec',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/jpa/repository/OptionGroupRepository.java': 'e22a907277076c2f3aeea99d605139c26f21574282ddf78ea23947b037a78078',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/jpa/repository/OptionValueRepository.java': 'd44fa6ea48d98f6c1c83d1fc9c27d484fe40ad86f551e272721e410328cbb2f3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/jpa/repository/SKUValueRepository.java': '9b51dc120e9e632b45ee832dccd81ecfde0bb28dd838e400e4d530229548d87c',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/CatalogManagementExceptionHandler.java': '2bdd1388ceb622a598a3c1756627bfaffdffc58186dabc0e846f0203b26435dd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/OptionGroupRequest.java': 'efea16bdecb2ca939cc083e62d05547e8805b59c0383553931811f1363deb716',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/OptionGroupResponse.java': 'abf39ed75afed69002b3c4f410e87f7d514dcf5496ca89e8eeaa1b800222e805',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/OptionManagementController.java': '4e5922b7c4a74c84084224ca488845a9d3fe5e8dd49f29982cbd84d2749945b3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/OptionValueRequest.java': 'cb98fe419dfdfe9a12000797e5de0d5e32c35eddfeee7309da093e948ccbc865',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/OptionValueResponse.java': 'a765589e14bca2705fd83ad12f4fec29a8ffe30123695748e9df2a09382a6ca8',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/ProductCreateRequest.java': '71511512e42c62916440e3f8b1b9d768c97f34e21ea847359b4437b0e1f40aa0',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/ProductImageController.java': '135694ec31b0f09391d24f2e83c8ef5ca588bfbed49b83101ec7fc3761e8e77f',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/ProductResponse.java': 'f7e6444db0d6e199f0170cd65c96dc2c8b2b7ebc6335dbc06abaa8b78d761c5f',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/ProductUpdateRequest.java': '46bfdb0771ccb5075970381a28c3c78d3b70245c7c4addd071ef578adbcdd24c',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/ProductWriteController.java': '34e1337594c2250dc076fe20431a0bb730d0496f95680f2842a69f004372d2cd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/SkuCreateDto.java': 'f0078bd7e2402b28ff418f00e08071c1d41dd7b5b0877cf5a7c8dd2e18186ca1',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/SkuDetailDto.java': 'a84afcaa7a1a61a7d3c94663597ccfbc435ed2ee4d7e4d517218c2b66524294e',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/SkuUpdateDto.java': '1e24bb04c87070d3cb5c046fae2336ca465e004716566dd9e4125fbaad54a01f',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogOptionsTest.java': '3a3614f63403c032268e4a2a449a2869e41d391fb19b856132c927fd12a26329',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogProductsTest.java': 'dc2746474b89be100b86a8a23b1ceeded2b620f295ee9a32e16df6890c0ffbce',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/ProductSkusTest.java': '618cfdc4384aa92545bc55656a2e0af571a7651ffebffcc90dc871d719663b77',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/S3ProductImagesTest.java': '83b249918f002f53ab5c27841d60089500f46cb7bcd95b0c21d0ff49b0e933c2',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/web/CatalogManagementControllerTest.java': '35cfe0901d2e2e9d16ca5b0e3907457472effb44ab48aee0e6266788ff28e5ae',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreCatalogManagementHttpTest.java': '7d24752318a277f07285b27a6eef2689a9eca04e367f6e58a701a2cddd425bd4',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/PaymentConfirmationConfiguration.java': '88039865aef33fd44cb732226ce02d8c5ebd1be64f4292c76000c75b020d4e9a',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StorePaymentEventsConfiguration.java': '35193aea6b8bee60ea9976a1666628d32854e9b71340983c8043d0a7bb728177',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StorePaymentTransportConfiguration.java': '923a41fff420b5b72113022f221c4b4f754cc0b17761cc72171177516548043d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PaymentConfirmationConfigurationTest.java': 'fa99275028ba6c2960e94c06ec7595502c29b7bab2e512bf1a042df28e0470b1',
 'backend-app/boot/src/test/java/com/pawbridge/backend/PaymentConfirmationHttpTest.java': '869f898af01a6409542f90dfb7149aa34067db626203c24e5b07a031c2a3d41c',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StorePaymentEventsConfigurationTest.java': 'b3c042a9b9ab6cfef6a9a8f6a81168dd259c588d498f13587adbec3bdb5dd72a',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StorePaymentKafkaTransportConfigurationTest.java': 'ff93e0cc4553a99fa52cb127b56e8ccaf46e1c30e1514ef3665e6a087fe21188',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/StorePaymentTransportConfigurationTest.java': '74ab40a488a080df504fe4c8b06fb8e0943e6dd66b0cf700bd27c295c0e3b50b',
 'backend-app/payment/build.gradle': '704a2765b5699b61ed0b752ed6a55a52e93b5c05b3dd1fb06f5d260553752050',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/application/Payments.java': '580fa37f5b90f11fdb415ecc437be7ba7c5967a7b69a362d24e8ddf3caa4aecf',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/application/dto/TossFailure.java': '95b15e1551712f1a463be154229460dad1d5591bfb3f0f44dc01b3d7aed51c6f',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/application/dto/TossPaymentCancelRequest.java': '982b80c89450eaf10e7ced0ff4d378109d237dff3304e207c05910aff104a486',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/application/dto/TossPaymentConfirmRequest.java': 'f6162fef3ad79cb828e16e594158026911bad8c5c0853caa68f7b348ddd0e673',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/application/dto/TossPaymentResponse.java': '935de60036aa035168334ed8b4b02a2470c03725569285ae25e0b4c8a0172e48',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/JpaPayments.java': '2a64b7a1480b2b5571d3d5088cdf7e6fbb99cd9063a44fa88215c86d65f562bb',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/model/BaseEntity.java': '71d8685d22dfcbb9267f30d58929f666f83bcfbdf1519d3bcea0f208207e1bed',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/model/Outbox.java': 'a4768361ed601b1806a953d797091c2399a5f35b2dccde93255b6fbe20f79ebd',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/model/Payment.java': '5ef3aec28f6debe4f9132557ca276ca897e66c9422baa9113ecd2d990abab516',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/model/PaymentStatus.java': '232f8bb3490156e71bfb3bd2451ab95cf5b9352af73ee8adc9eaf0756d5ea122',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/repository/OutboxRepository.java': '6effe581b19c952714119abde690ff6978e8f09d9ce955d1dffcb3c6c2593345',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/jpa/repository/PaymentRepository.java': '736846b02f8f784649c14c6e2d0d248f7982b853acc66a0884fdb12571edb0b5',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/toss/HttpTossPaymentsClient.java': 'd575e6e59028dd4040a5f366ddf3bdda28f904d7c662c751bc6b450631299ca3',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/toss/TossPaymentsClient.java': 'b0d8983872ef0402586205e8f795b63ab6b8935e2abf150453870af940bfeb12',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/web/PaymentController.java': '456728fa9018d3577415bce3426f3027cd3d956339e1e9c08e615eebe368b17a',
 'backend-app/payment/src/main/java/com/pawbridge/backend/payment/internal/confirmation/web/PaymentInputExceptionHandler.java': '90be6e914177f793fdd20b5589b137e15cedb1b6b195d06aa3beb4e393e966bc',
 'backend-app/payment/src/test/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/JpaPaymentsTest.java': 'a3dcce51e5d9d2e9ca44ec6a105db8088da9aaef2bfd150a0ce684390dd79318',
 'backend-app/payment/src/test/java/com/pawbridge/backend/payment/internal/confirmation/infrastructure/toss/HttpTossPaymentsClientTest.java': 'aae5568405b14d5ff20d49b3cc8592d0df09cc52f6d55d16d57e6dbfacaf105a',
 'backend-app/payment/src/test/java/com/pawbridge/backend/payment/internal/confirmation/web/PaymentControllerTest.java': 'ba4053ae717007436b22cec0570c48cbc24a9212f64e0d3c3e31bc3653d5aacd',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StorePaymentEvents.java': '949dfe39b030f4571be7bd7ab0ea1db21827fac519f82309dcb71f54bcac1998',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/api/StorePaymentOrders.java': 'efc7eb747373894241acc8af9a9d63c8ae35b3f4aabb5c6eef1b6e73e286aad3',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/payment/infrastructure/JdbcStorePaymentOrders.java': 'bfc5cba913d7d1aa25a580c19ce3707a7d0c8fb032b946ab63d9142ebe834234',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/payment/infrastructure/JpaStorePaymentEvents.java': '9e000a519b06e6941ff40a2452ca7a974457ffc7268ec0537af94f86e3102067',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JpaStockRestorationTest.java': 'c14e22d88014901bfb7dc57c81de486669670d9a2385b1001bd40886b17a2c46',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/payment/infrastructure/JdbcStorePaymentOrdersTest.java': 'cccd4bed47f642eb77c87a8eca0cd25fca76583642a289d2c7decea5c450d595',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/payment/infrastructure/JpaStorePaymentEventsTest.java': '0fca81886769bae059e6a05a271af267b010f87310371995d787f2c5fbbdd716',
 'backend-app/boot/src/test/java/com/pawbridge/backend/OwnedPostgresqlMainCoreIntegrationTest.java': '3348cf23af9b014da09301bf4feab3d6f6d61ba559c155a2a52be91c41aa78f7',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/OwnedPostgresqlShelterObservationTest.java': '0d3401d0c5c1d7c8ead47d010d750afd0e6ae1b1e19c4934f30be1f52742c2bf',
 'backend-app/boot/src/test/resources/owned-postgresql/main-core-owned-schema.sql': 'c29b9adc5c085fd8d31d1ab3eb019a369d64d29f73debfbf78055319ccfaa294',
 'backend-app/boot/src/test/resources/owned-postgresql/shelter-observation-owned-schema.sql': '98a1b79a86c43820beba5d3a8fabeda0845e3231dfb308e88f00a4ae367d3c7f',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/LocalLoginConfiguration.java': 'aeea882b9e935c87eafb5468d04442ad17057b21ba877cc40509986e2a640db4',
 'backend-app/boot/src/test/java/com/pawbridge/backend/LocalLoginConfigurationTest.java': '73871cdb5c8138df172990a8feeb238501febbf9ef6472a530839b843d50a969',
 'backend-app/boot/src/test/java/com/pawbridge/backend/LocalLoginHttpTest.java': '37a07cce5c2ec94c25adc1f3f5bb9054448808dd843ac809c978fc9ff4d9bd78',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/login/application/LocalLogin.java': '979acb1d4bdc403d1a7a3ab604b952f52ebd4dc3862cf59ee248c56260506a96',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/login/infrastructure/JdbcLocalLogin.java': 'f87bd9012be5a1e6c8e44509f763d3e7f7d6058d8fa48fedf9d3f094d4308299',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/login/web/LocalLoginFilter.java': 'ece67854ca66cadccb528a4a7e18aa18d93797197ecce1309acf28edaeb6ead1',
 'backend-app/user/src/main/java/com/pawbridge/backend/user/internal/login/web/LocalLoginPrincipal.java': 'd4654490da6610beaaeff851c50a97bdb9c60d5f6740e9c6514a93862abe0e30',
 'backend-app/user/src/test/java/com/pawbridge/backend/user/internal/login/infrastructure/JdbcLocalLoginTest.java': '70730e495439917b826657c6b9f4659dd4b28d220295784138050ee8af1f652d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/OwnedPostgresqlLocalLoginWriterTest.java': 'e74579a0364eccf29350850ebcc0dfa7181fe808e532c107bf73550f94c0fc5d',
 'backend-app/boot/src/test/resources/owned-postgresql/local-login-writer-grants.sql': '4fb2d62a1f8504ab0325f5aadf5c2c09c2890370bb9b2005e29d1a0d46af4f3a',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogCategories.java': 'fd3410a929239bb29dcdb5bf4857a661e6a21aaf1a0dc573c4f5750271a72389',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/CategoryCreateRequest.java': 'b97368a60b943613f678457c06a2104db24f0470b6555c0e697434cac0fb5cde',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/CategoryManagementController.java': '84cefb711e37c30729a85a1fcef4472e27e7b0c8f788b35c67ad0ccd05f1b5f5',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/CategoryResponse.java': '80f3d52176d32bc68b2e2abc7dfb671517a25b8be047ed4e7802777199ef6e7b',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/catalogwrite/web/CategoryUpdateRequest.java': 'a95fe1cf6aac8f8349bb083253dd80cf1195c7a96b33d964cda9508e409aa22b',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/catalogwrite/infrastructure/JpaCatalogCategoriesTest.java': '2fc887b68ef1718e145427756d40b1cf8aef4bed3191d42c99cf6cd5fd33dfb7',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/StoreMaintenanceConfiguration.java': 'e4306968c2f50399d41aecca881416a893a413cbd0148186f03a555f3c043c9d',
 'backend-app/boot/src/test/java/com/pawbridge/backend/StoreMaintenanceConfigurationTest.java': 'de28d233e4d1fcc1c54a82f0d947dc165aecbff06e94711e10d3583f507f7073',
 'backend-app/store/src/main/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JdbcProductSearchRebuild.java': 'e9b2d78621959dca2cf60ec0f3d5062da18ce0109e1a41086dbd3f26fa966be3',
 'backend-app/store/src/test/java/com/pawbridge/backend/store/internal/directorder/infrastructure/JdbcProductSearchRebuildTest.java': 'd6ab5ce060466505bc1793ceefc685acee25b29d1bcd55272d648039f17c1f54',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/PostgresqlShelterDirectorySessionLock.java': 'd859b0970da31578007a1e35720f2720aac0c2e7b4ff12f2a89adf802a7b3fc6',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryClient.java': 'f9b0a814565194cf02f68ecb09b2e1085d7e9c196d7c1bf8663a6f8dea46d353',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryCollector.java': 'c4246a57eca64069b643ece25e38b6e3a0a44a9a92dffe3388a33fd1ae88b10f',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryStore.java': 'c2f7192798f840ada02911bf991de3cea82a753c17756312ad812e171c49acad',
 'backend-app/animal/src/main/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterPublicDetails.java': '4fbb2515d51ef3c6e5ffd4239ae3c4c5914c8d6b1f1aaecd3c12dd89d8486680',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryClientTest.java': 'bab7e20e89e8109dfa7f02c041d856cdbd96c010aecfbe4c270dae63c7445524',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryCollectorTest.java': 'f8cd663da4a563edc1c24bff4c07fcde29358d369d3a90091e1f80da6f86debd',
 'backend-app/animal/src/test/java/com/pawbridge/backend/animal/internal/shelterdirectory/infrastructure/ShelterDirectoryStoreTest.java': '4f356d37b18921cb10e9d3d37e97a096970291841e0a3461de04d7325bce9707',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ShelterDirectoryConfiguration.java': '0c97242ee1dfe2298fea1ea3e66673d8cee78f32d74620994b44e7d970fbd7d7',
 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ShelterDirectorySchedule.java': 'c2e91595db734fab5386ecde07e6f96819a4d34de26c9369349327001dcba3c9',
 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration/ShelterDirectoryConfigurationTest.java': '5eb9029254f3f65ef6736aabc846c4f17a894f85bb37fd4ab6fbd83a2e67d0ec'}
# Only the identical, previously successful TestKit inputs may be referenced.
# Actual architecture and compiled business-package checks still run on every candidate.
ARCHITECTURE_SELFTEST_REFERENCE_RUN = 39577223965
ARCHITECTURE_SELFTEST_REFERENCE_HEAD = 'a89cf733742d86c8d11982b2586adeb78a11fbde'
ARCHITECTURE_SELFTEST_INPUT_HASHES = {'backend-app/animal/build.gradle': '2ff3037bc77f583f34c474a3213fb41e2d8242b63d75df85d191000dce4a71db',
 'backend-app/boot/build.gradle': '8bbc11e2d1bb5db82d01eef7d3466f5ff57fec56f67823a7beff6284c5b9d7f6',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/BusinessBuildFixture.java': 'ec8457df5ae2a9f0c75ea3f729ad19c90041d1b20cb3bcfa0d51365ef6045a23',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/BusinessPackageArchitectureTest.java': 'fb01c5732e6df20e037a02895b752aa5bf354eac5e57659ac9dd015da61c9dc6',
 'backend-app/boot/src/architectureTest/java/com/pawbridge/backend/architecture/GradleArchitectureTest.java': '10be33ae04d89db2c79731780a42624beec5d1460b1bc04e13c7d9b3749122b4',
 'backend-app/build.gradle': '1633e17fa53b9172da0233ea1ec99d85c5969d498c6656fa338dbfdfc672a267',
 'backend-app/community/build.gradle': 'cae9d8a06febf4ceb5abe9a7728ed663ec07164883f85e01cba1f23fe3737408',
 'backend-app/gradle.properties': '4491e9ef524b507cce154ba37ed5ff7618bedf95a2546dc7c73d0d826ab87f14',
 'backend-app/gradle/architecture.gradle': 'ca825f609fd21787986bdbc0e52668fcb73fa29b180796830c583c601c6b2e5d',
 'backend-app/gradle/wrapper/gradle-wrapper.jar': '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172',
 'backend-app/gradle/wrapper/gradle-wrapper.properties': '05fcb55a155233ee632da57149d1ba3cd39a001701ae00e0c20782fbaf6c378b',
 'backend-app/gradlew': 'b187b4c52e749f5760afdd6fadc31b2a98ad35fb249bf0dff03b72650f320409',
 'backend-app/gradlew.bat': '94102713eb8fb22d032397924c0f38ab2da783ba60d07054339f1190a0c4e2cd',
 'backend-app/payment/build.gradle': '704a2765b5699b61ed0b752ed6a55a52e93b5c05b3dd1fb06f5d260553752050',
 'backend-app/settings.gradle': '0fbbfd2e3fdcb234148d42964ba69ff5b410b206ceb339ae79a03203e276de7e',
 'backend-app/store/build.gradle': '2f473c585123588bf9e9e68ddf52a66663352470a8d3626ff8cef0c2c7be1eed',
 'backend-app/user/build.gradle': '2618227c07294560b5e318808d990152272acef69c1a071d7dc2a44d656f0835'}
POST_READS_APP_EXCEPTIONS = frozenset(POST_READS_APP_HASHES) | {'backend-app/README.md'}
POST_READS_ALLOWED_PATHS = POST_STATISTICS_ALLOWED_PATHS | frozenset(POST_READS_APP_HASHES) | {
    '.github/scripts/test_backend_admin_post_reads_profile.py',
    '.github/scripts/ci_branch_policy.py',
    '.github/scripts/test_ci_branch_policy.py',
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
                or len(POST_READS_APP_HASHES) != 697
                or not re.fullmatch(r'[0-9a-f]{64}', POST_READS_WORKFLOW_SHA256)):
            return False
        workflow = git('show', 'HEAD:.github/workflows/backend-app-check.yml')
        if not isinstance(workflow, bytes) or hashlib.sha256(workflow).hexdigest() != POST_READS_WORKFLOW_SHA256:
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



def verify_architecture_selftest_inputs(profiles, git_reader=None):
    """Reference only this successful fixed guard input set, never application checks."""
    if type(profiles) is not list or profiles != [POST_READS]:
        return False
    if (type(ARCHITECTURE_SELFTEST_REFERENCE_RUN) is not int
            or ARCHITECTURE_SELFTEST_REFERENCE_RUN <= 0
            or not re.fullmatch(r'[0-9a-f]{40}', ARCHITECTURE_SELFTEST_REFERENCE_HEAD)
            or len(ARCHITECTURE_SELFTEST_INPUT_HASHES) != 17
            or any(not re.fullmatch(r'[0-9a-f]{64}', digest)
                   for digest in ARCHITECTURE_SELFTEST_INPUT_HASHES.values())):
        return False
    def read(*args):
        return subprocess.run(['git', *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True, timeout=8).stdout
    git = git_reader if git_reader is not None else read
    if not verify_post_reads_inputs(git_reader=git):
        return False
    try:
        for path, digest in ARCHITECTURE_SELFTEST_INPUT_HASHES.items():
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
        'selection_reason': ('fixed-user-community-inputs-and-scoped-admin-read-postgresql' if profile == POST_READS else
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
