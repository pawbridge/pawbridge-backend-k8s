#!/usr/bin/env python3
"""F29 selected original send; immutable shared closure; extraction starts no runtime."""
import hashlib
import pathlib
import re
import subprocess
import sys

SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'

SHARED = {'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMember.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/userservice/contact/ContactMember.java',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java': 'backend-app/boot/build/generated/sources/f17LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java': 'backend-app/boot/build/generated/sources/f2LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
 'community-service/src/main/java/com/pawbridge/communityservice/client/UserServiceClient.java': 'backend-app/boot/build/generated/sources/f2LegacyOracle/java/com/pawbridge/communityservice/client/UserServiceClient.java',
 'community-service/src/main/java/com/pawbridge/communityservice/util/ResponseDTO.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/util/ResponseDTO.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java',
 'community-service/src/main/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java': 'backend-app/boot/build/generated/sources/f16LegacyOracle/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ApplicationException.java': 'backend-app/boot/build/generated/sources/f7LegacyOracle/java/com/pawbridge/communityservice/exception/common/ApplicationException.java',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ErrorCode.java': 'backend-app/boot/build/generated/sources/f7LegacyOracle/java/com/pawbridge/communityservice/exception/common/ErrorCode.java',
 'community-service/src/main/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java': 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java': 'backend-app/boot/build/generated/sources/f26LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java'}

SERVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java'

CONTROLLER = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java'

ADVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

SERVICE_CONSTRUCTOR_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'signature': '    public PrivateNoteService(',
 'start_anchor': '    public PrivateNoteService(',
 'end_delimiter': '\n    }\n',
 'sha256': 'a335004ace939992295e58c01267b010c975457a70d2324b86d9d9f7c13bd7c6'}

ORIGINAL_TRANSACTION_FIELD = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private final TransactionTemplate transactions;\n',
 'sha256': 'fb0c0e8852f7e4a57ae9c4656fff11404c3c992d209626f9f6cc0b83266e5b44'}

TRANSACTION_CONFIGURATION = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '        this.transactions = new TransactionTemplate(transactionManager);\n'
                  '        this.transactions.setTimeout(15);\n',
 'sha256': '4de4c93dd1f0d040d20fe7f4a246625ba10b7ff25290412c85bf0ab2fd77e91e'}

CONTROLLER_ANNOTATIONS = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
 'start_anchor': '@RestController\n',
 'end_exclusive_anchor': 'public class PrivateNoteController {',
 'sha256': 'e1bf368f9ec65be4043c8172587c028eeb2e96205e32c6788dfbd0a0feb31b51'}

ADVICE_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
  'signature': '    public ResponseEntity<?> status(ResponseStatusException failure) {',
  'start_anchor': '    @ExceptionHandler(ResponseStatusException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'bff5a44283a271b2494ffa43e646d558571da496138eb60f7518042c7988681c'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
  'signature': '    public ResponseEntity<?> storageFailure() {',
  'start_anchor': '    @ExceptionHandler(DataAccessException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': '0eed4568d0c73520d4ab15f4fa0bb18cbe871cfa28f349be37f496a44193daa2'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
  'signature': '    public ResponseEntity<?> invalidRequest() {',
  'start_anchor': '    @ExceptionHandler({\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'e73564ce85a86cde97a749e4a69d1716356dce3ef96907589de5cb17c53e32eb'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
  'signature': '    public ResponseEntity<?> unexpectedFailure(RuntimeException failure) {',
  'start_anchor': '    @ExceptionHandler(RuntimeException.class)\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'd60ed4bb71e3c022a88f9ee31e369cdc84f5a35cd26419e421cccc4257b0e6ba'}]

ADVICE_ANNOTATIONS = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
 'start_anchor': '@Order(Ordered.HIGHEST_PRECEDENCE)\n',
 'end_exclusive_anchor': 'public class PrivateNoteExceptionAdvice {',
 'sha256': '7d869d52175a7dedac9ef0c9eaccea02978ef242da5218828853af357e8f63a2'}

LOGGER = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java',
 'verbatim_utf8': '    private static final Logger log = '
                  'LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class);\n',
 'sha256': 'a5f9f551b1e478d7086f34f253b40b39256b118ad2db7e81e90e6e25dd9eab77'}

MODEL_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record ContactMember(Long userId, String nickname, boolean active, boolean '
                   'deletionPending) {}\n',
  'sha256': 'c037d57bad5a40e651d625333788ff4267b5803b84825fdfc006943e885a6448'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record SendNote(\n'
                   '            Long recipientId, String body, UUID requestId, UUID replyTo,\n'
                   '            String contextType, Long contextId) {}\n',
  'sha256': '378207f12f8e2749f6a0b3b2cb5c7a22f45989a236489e6a13e7319e80d097dd'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record Receipt(UUID noteId) {}\n',
  'sha256': 'e41ead7c44f3c35dd03274683ccdf983eefd9e77181539ba26d3039715d24f4e'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record Notification(\n'
                   '            UUID noteId, String kind, Long actorId, String actorNickname,\n'
                   '            Instant createdAt, boolean read, String href) {}\n',
  'sha256': '51699142fdbcd76cf7f329a56cae21535603da28e5d88286a0da266e21791e76'}]

DDL_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_notes (',
  'end_delimiter': ');\n',
  'sha256': '4663bf9d3537c754a36c8ae014b802039767bcbf42e30868e8bade173143ae08'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_mailboxes (',
  'end_delimiter': ');\n',
  'sha256': '898a9dcfee52f958ed6a8f77fdd1960bd48562af3f33feea7b97a2fe0950ab4a'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_requests (',
  'end_delimiter': ');\n',
  'sha256': '0a794d8711da88e4c98ed98fe931d2b775dd29037169cd9c1655a34ad91d83bc'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V4__animal_reports.sql',
  'start_anchor': 'CREATE TABLE animal_reports (',
  'end_delimiter': ');\n',
  'sha256': '22b35d7107312096c32c3ae7023a7e78fd6d97d396c286bdae17bb1b50b9c8ea'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V5__animal_report_search_fields.sql',
  'start_anchor': 'ALTER TABLE animal_reports',
  'end_delimiter': ';\n',
  'sha256': 'bd354d203af70dc5afaf89b25d78322cd751a9c8e199d6ef7e2cba49df9f7b76'}]

INDEX_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_rate ON private_note_requests (sender_id, '
                   'created_at DESC);\n',
  'sha256': 'a7744fbfb946dfb2e7b42168a4dbf99a52bec4978d88dea24125007495b5822f'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_note ON private_note_requests (note_id) WHERE '
                   'note_id IS NOT NULL;\n',
  'sha256': '65a253fa5f95bd592ff66373daf3fd7d41614c8c9a1aeaed5f6a6db071036fbc'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_cleanup ON private_note_requests (created_at) '
                   'WHERE note_id IS NULL;\n',
  'sha256': '020b3e8baa54267e0699f878ccabd5cfbb68d35ff187d381491910315ec16ab0'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V4__animal_reports.sql',
  'verbatim_utf8': 'CREATE INDEX animal_reports_kind_created_idx\n'
                   '    ON animal_reports(report_kind, created_at DESC, report_id DESC)\n'
                   '    WHERE deleted_at IS NULL;\n',
  'sha256': '8896b1fa54b0859fe32deef8fc7d959d69a7c297e185692e4018d5121c4294e7'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V5__animal_report_search_fields.sql',
  'verbatim_utf8': 'CREATE INDEX animal_reports_region_occurred_idx\n'
                   '    ON animal_reports (province, district, occurred_on)\n'
                   '    WHERE deleted_at IS NULL;\n',
  'sha256': '83acfa67eaf9e0f0a4c75c48b393ca45020724d239d13269bef5cc4f5c68f198'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V5__animal_report_search_fields.sql',
  'verbatim_utf8': 'CREATE INDEX animal_reports_type_occurred_idx\n'
                   '    ON animal_reports (animal_type, occurred_on)\n'
                   '    WHERE deleted_at IS NULL;\n',
  'sha256': '591e30398f83e66a3e77f01d1ff8fbb0e68bf8a1946c89c646ee6f99c622d707'}]

F18_ADAPTED_PATH = 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

F18_ADAPTED_SHA256 = 'fc02a8aefbe87a5f9b3e7d3017334d0b38d1fb7fe5b499cc080528e6cc1a03bf'

F18_ADAPTED_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n')

ORIGINAL_CLOCK_FIELD = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private final Clock clock;\n',
 'sha256': 'a7bcbb30ed951787b3ea090bbeee27f2d5395b6a9fb0d8f2a293fb9a332d49ce'}

ORIGINAL_CLOCK_ASSIGNMENT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '        this.clock = clock;\n',
 'sha256': '3a52b7823bda5b28ef3fed4844f45438d1e1c1782027e70267dd45b242860599'}

EXPECTED = {'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java': 'f0118b39e5b4a99b7592ecfd3257f4f11142306355d0503a52ec48c0da251acb',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java': '3c27ab84fa9326c9de8e0bd112b7f47778150fce7c5cdae0f3073bd316898303',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java': '99c39401f41509a6fb05b5f68ae967fe5e08a32d6dcee399cb39b1f7dc9bce3b',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java': 'b6b5f3b516bc5118a3ee0ea73945df9ee9846e78bcf7d6dfee81d711e30a7ed3',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMember.java': '467aed47cf77982a7f30ae5efa318b78981449f80896f003eb82cae80ae8a732',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java': '287e88daea9abae4624a94aa89af794454aec47b3322156c5ba74315e782198e',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java': '06cc66d2e6b6d269bd51f30338d80fe33cdba4ef1085f2e2f1f0413ffdbfe772',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java': '5f4c2e1026025aedaaed24922faf440805323757c3b2d2589cf69f80da0f849a',
 'community-service/src/main/java/com/pawbridge/communityservice/client/UserServiceClient.java': '7dfe06774f5e44c18597cbc9f1b3b0670880807f19e70df5ce76c26ff41be216',
 'community-service/src/main/java/com/pawbridge/communityservice/util/ResponseDTO.java': 'ad38765231567373be85c0ccc8027a49346a3a462fbac59d3a7c6e75d2db6327',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/GlobalExceptionRestAdvice.java': '978a02329f24d5ebe36607703ad404069ce8eeb03738bcf6dbfbdd6dd23a5519',
 'community-service/src/main/java/com/pawbridge/communityservice/persistence/PostgresqlRollbackCharsetViolation.java': '7def844fd8f0aa83e0f32bc4de37d333093a445b481e70a7289a115ed3653b15',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ApplicationException.java': 'adf8468fe9963a747e72d3edcfd92f19dd8d0b5350555c32cfea6d34cca8fe97',
 'community-service/src/main/java/com/pawbridge/communityservice/exception/common/ErrorCode.java': '1202c261eb35c0ea914a2425b7a647e6b60c9bb205bf31be19c57d356dd18606',
 'community-service/src/main/java/com/pawbridge/communityservice/search/InvalidSearchInputException.java': '9d0f1779c6bd86095e7dda06295f96526736b5f37126b95331ed1a7b81624781',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberController.java': 'de0349f5051e19cc4cebe4581e65876cfffe226f4d4555bf0f70d1b4c136a7dd',
 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql': '3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119',
 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql': 'f1dcd3476bf56eb7862ffcdbdaa0546a59c94a0fb637c04c7a5d6fb677da1cc6',
 'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql': 'd2572afc2644df3f932872bcd757e47937cdf7d5b89789439cb103de6c71512d',
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java': '3215dd7b9f3958049ea3065bc550883838bfeacc46d75e350cbc29f26a9504eb',
 'community-service/src/migration/resources/db/postgresql/V4__animal_reports.sql': '6f1d2a9c75e1fb2ec836f5e4cd8fecbf68fd0c2018a20a79193edd27747dd71a',
 'community-service/src/migration/resources/db/postgresql/V5__animal_report_search_fields.sql': 'ff29faf3026342725bf585cb5feebf33f617a34c14e0b038c35ea29276c1f213'}

STREAM = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java'

SERVICE_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    public Receipt send(long currentUserId, SendNote request) {',
  'start_anchor': '    public Receipt send(long currentUserId, SendNote request) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'ef217e4cec3c28a9c4af8a877b81842d1ceb98b7cd66bbbed6ea479b2104ff4a'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static String validate(SendNote request, long currentUserId) {',
  'start_anchor': '    private static String validate(SendNote request, long currentUserId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'd2e1c7d48d5cec836d10770263b637e9b9ffdb38d7b790fb6860930a6218eab4'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static String hash(SendNote request, String body) {',
  'start_anchor': '    private static String hash(SendNote request, String body) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'e9b16e96baf624bb78c07a12e068283b24c9403de6cbc1062adafd5c7a7c3e97'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private void validateReply(long currentUserId, SendNote request) {',
  'start_anchor': '    private void validateReply(long currentUserId, SendNote request) {',
  'end_delimiter': '\n    }\n',
  'sha256': '4aa7ff377d8daec0584628857eb850efaff0a89a1cea3e64c978e07ede7f69a8'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private void validateContext(SendNote request) {',
  'start_anchor': '    private void validateContext(SendNote request) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9c6e5c588d3b927df38c82773ba4a21a79806f5aea82c7e0a6a485cdc8e24351'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private Note find(long currentUserId, UUID noteId) {',
  'start_anchor': '    private Note find(long currentUserId, UUID noteId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '560179dfe2682c6c22895003c74d3c978ad7643917a17578888aae8b40dcca99'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private ContactMember active(long userId) {',
  'start_anchor': '    private ContactMember active(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '20717cc58bc2264b90a12157a8d868ef69e04a8baa949936489c82f26003d745'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private ContactMember lookup(long userId) {',
  'start_anchor': '    private ContactMember lookup(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9667ffbd6c876303de0f5b02929aed4ab5dc3d4ce50c737e464e62d292379bbf'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException memberLookupUnavailable() {',
  'start_anchor': '    private static ResponseStatusException memberLookupUnavailable() {',
  'end_delimiter': '\n    }\n',
  'sha256': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static void afterCommit(Runnable action) {',
  'start_anchor': '    private static void afterCommit(Runnable action) {',
  'end_delimiter': '\n    }\n',
  'sha256': '700eb05797bbc27e4c3b531baae655d696c7b55d2d7afc8247c972f68f0a389f'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static void requireId(long userId) {',
  'start_anchor': '    private static void requireId(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'start_anchor': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'end_delimiter': '\n    }\n',
  'sha256': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad'}]


# F30 adds only the source void expiry entry; the raw shared Repository already contains both DELETEs.
SERVICE_UNITS.append({'source': SERVICE,
 'signature': '    public void expire() {',
 'start_anchor': '    public void expire() {',
 'end_delimiter': '\n    }\n',
 'sha256': '7cb174de49561324b4a313901c757138d18f6cabc826fff0a363fbd5c412ad14'})

CONTROLLER_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
  'signature': '    public ResponseDTO<Receipt> send(',
  'start_anchor': '    @PostMapping\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'c8ff34f6790e5bf71b4aa19e11bb6b8eb0a895084c7b7bf353543178f99da102'}]

SOURCE_EVIDENCE_UNITS = [{'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    public ContactMember get(long userId) {',
  'start_anchor': '    public ContactMember get(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9d17e0ed651f3f48437962a87126bf14fb149a8a8eb038a191ad37d792687619'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void lockMembers(long firstMemberId, long secondMemberId) {',
  'start_anchor': '    public void lockMembers(long firstMemberId, long secondMemberId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'f1b4076d5e324c802778f05e7235d1daf02caf46f25f3f0b858092445e24481f'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public Optional<Request> request(long senderId, UUID requestId) {',
  'start_anchor': '    public Optional<Request> request(long senderId, UUID requestId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '6ca85a7f9c09b242a109b244830bbc77195f635d227bd29f9ef5768a05d1b814'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public long rate(long senderId, Instant since) {',
  'start_anchor': '    public long rate(long senderId, Instant since) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'fbc51497dbed2c6f1ff1e9d3265f6ecbe192d452643ff133233ff75e090976c9'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public boolean blocked(long firstMemberId, long secondMemberId) {',
  'start_anchor': '    public boolean blocked(long firstMemberId, long secondMemberId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'fd399232b50c0b6b96305d10a3ab5262eb352d6f9fbefb711b73d3f87d5b73ac'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void insert(',
  'start_anchor': '    public void insert(',
  'end_delimiter': '\n    }\n',
  'sha256': 'fc205f1f87c0baeee61309a8fb569e137490f75d82f99d89f491f2bf471aaa30'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public Optional<Note> find(long ownerId, UUID noteId, Instant now) {',
  'start_anchor': '    public Optional<Note> find(long ownerId, UUID noteId, Instant now) {',
  'end_delimiter': '\n    }\n',
  'sha256': '13a97b5340ffde6321705796190837e63cda3e98ca5f63b1ac0357dd59b3d39c'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public boolean contextAuthor(String contextType, long contextId, long recipientId) {',
  'start_anchor': '    public boolean contextAuthor(String contextType, long contextId, long recipientId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '8ed810aa62ff11d227a4e7b549111f6a792de49b68eb41faeb22a6f096857392'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java',
  'signature': '    public void publish(long memberId, Notification notification) {',
  'start_anchor': '    public void publish(long memberId, Notification notification) {',
  'end_delimiter': '\n    }\n',
  'sha256': '00d44e77f7c18d005895e06904579714778b03fdc97b3c7ab708d89a73c4b0e7'}]

OUTPUT_ROOT = 'backend-app/boot/build/generated/sources/f29LegacyOracle/java'

EVIDENCE_PATH = 'backend-app/build/ci/f29-oracle-sha256.txt'

OUTPUTS = ('com/pawbridge/communityservice/contact/F29LegacyNoteSendService.java',
 'com/pawbridge/communityservice/contact/F29LegacyNoteSendController.java',
 'com/pawbridge/communityservice/contact/F29LegacyNoteSendExceptionAdvice.java')

ORIGINAL_STREAM_FIELD = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private final PrivateNoteStream privateNoteStream;\n',
 'sha256': 'e526e0680d0536ba2764601a8dfe5a6ebf1ba2c148e1055954b392e8907fbacc'}

SERVICE_LOGGER = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private static final Logger log = '
                  'LoggerFactory.getLogger(PrivateNoteService.class);\n',
 'sha256': 'e5340b4a475a1810388bc8ff1df13c67df3a28cbe042ef97b343f03dc21b96a5'}

CONTROLLER_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
 'signature': '    public ResponseDTO<Receipt> send(',
 'start_anchor': '    @PostMapping\n',
 'end_delimiter': '\n    }\n',
 'sha256': 'c8ff34f6790e5bf71b4aa19e11bb6b8eb0a895084c7b7bf353543178f99da102'}

ORIGINAL_SEOUL = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");\n',
 'sha256': 'a81d2cbce4b63e69ee0a691ea49dd5db690b6f31d11f05ee2e241b7333c685d2'}

F29_ADVICE_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F29LegacyNoteSendController.class)\n')

def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_method(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F29 selected method/annotation is not unique: ' + signature.strip())
    begin = text.index(anchor)
    try:
        signature_at = text.index(signature, begin)
        finish = text.index(ending, signature_at) + len(ending)
    except ValueError:
        raise SystemExit('F29 selected method boundary differs: ' + signature.strip()) from None
    selected = text[begin:finish]
    if not selected.endswith('}\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F29 selected method/LF checksum mismatch: ' + signature.strip())
    return selected


def checked_section(text, specification):
    begin, ending = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(ending) != 1:
        raise SystemExit('F29 selected class annotation boundaries are not unique')
    first, last = text.index(begin), text.index(ending)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F29 selected class annotation/LF checksum mismatch')
    return selected


def checked_literal(text, specification):
    selected = specification['verbatim_utf8']
    if (text.count(selected) != 1 or not selected.endswith('\n')
            or sha256(selected.encode('utf-8')) != specification['sha256']):
        raise SystemExit('F29 selected constant/model/logger literal or LF differs')
    return selected


def checked_fragment(text, specification):
    anchor, ending = specification['start_anchor'], specification['end_delimiter']
    if text.count(anchor) != 1:
        raise SystemExit('F29 source-only DDL fragment is not unique')
    first = text.index(anchor)
    try:
        last = text.index(ending, first) + len(ending)
    except ValueError:
        raise SystemExit('F29 source-only DDL fragment boundary differs') from None
    selected = text[first:last]
    if not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F29 source-only DDL fragment/LF checksum mismatch')
    return selected


def require_plain_path(path, label):
    if path.is_symlink() or path.resolve() != path:
        raise SystemExit('Refusing redirected F29 ' + label)
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise SystemExit('Refusing non-directory parent of F29 ' + label)


def reject_duplicate_fqns(repository, output):
    java_roots = list(repository.glob('backend-app/*/build/generated/sources/*/java'))
    for source_set in ('main', 'test', 'compatibilityTest'):
        java_roots.extend(repository.glob('backend-app/*/src/' + source_set + '/java'))
    for relative in OUTPUTS:
        name = pathlib.Path(relative).stem
        wanted_package = relative.rsplit('/', 1)[0].replace('/', '.')
        target = output / relative
        for java_root in java_roots:
            for candidate in java_root.rglob(name + '.java'):
                if candidate == target:
                    continue
                text = candidate.read_text(encoding='utf-8')
                package = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.MULTILINE)
                if (package is not None and package.group(1) == wanted_package
                        and re.search(r'\b(?:class|interface|record|enum)\s+' + re.escape(name) + r'\b', text)):
                    raise SystemExit('Refusing duplicate F29 wrapper FQN: ' + wanted_package + '.' + name)


def extract(repository, output, evidence):
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(value).absolute() for value in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / EVIDENCE_PATH:
        raise SystemExit('Refusing non-dedicated F29 output')
    require_plain_path(output, 'oracle output')
    require_plain_path(evidence, 'oracle evidence')
    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if sha256(content) != expected:
            raise SystemExit('Legacy F29 full input checksum differs: ' + source)
        inputs[source] = content
        entries.append(expected + '  source ' + source)
    for source, relative in SHARED.items():
        target = repository / relative
        require_plain_path(target, 'shared source')
        if not target.is_file() or target.read_bytes() != inputs[source]:
            raise SystemExit('F29 shared raw bytes differ: ' + source)
        entries.append(EXPECTED[source] + '  shared-byte-identity ' + relative)
    texts = {source: content.decode('utf-8') for source, content in inputs.items()}
    methods = [checked_method(texts[SERVICE], unit) for unit in SERVICE_UNITS]
    controller_methods = [checked_method(texts[CONTROLLER], unit) for unit in CONTROLLER_UNITS]
    checked_method(texts[SERVICE], SERVICE_CONSTRUCTOR_UNIT)
    for unit in (ORIGINAL_TRANSACTION_FIELD, ORIGINAL_CLOCK_FIELD, ORIGINAL_CLOCK_ASSIGNMENT,
                 ORIGINAL_STREAM_FIELD, TRANSACTION_CONFIGURATION, SERVICE_LOGGER, ORIGINAL_SEOUL):
        checked_literal(texts[SERVICE], unit)
    annotations = checked_section(texts[CONTROLLER], CONTROLLER_ANNOTATIONS)
    advice_annotations = checked_section(texts[ADVICE], ADVICE_ANNOTATIONS)
    advice_methods = [checked_method(texts[ADVICE], unit) for unit in ADVICE_UNITS]
    logger = checked_literal(texts[ADVICE], LOGGER)
    for unit in SOURCE_EVIDENCE_UNITS:
        checked_method(texts[unit['source']], unit)
    for unit in MODEL_UNITS + INDEX_UNITS:
        checked_literal(texts[unit['source']], unit)
    for unit in DDL_UNITS:
        checked_fragment(texts[unit['source']], unit)
    shared_advice = repository / F18_ADAPTED_PATH
    require_plain_path(shared_advice, 'adapted shared advice')
    adapted_expected = texts[ADVICE].replace(advice_annotations, F18_ADAPTED_ANNOTATIONS, 1).encode()
    if not shared_advice.is_file() or shared_advice.read_bytes() != adapted_expected or sha256(adapted_expected) != F18_ADAPTED_SHA256:
        raise SystemExit('F29 adapted shared F18 advice differs')
    entries.append(F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + F18_ADAPTED_PATH)
    service_logger = SERVICE_LOGGER['verbatim_utf8'].replace('PrivateNoteService.class', '"com.pawbridge.communityservice.contact.PrivateNoteService"')
    service = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Note;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Receipt;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Notification;
import feign.FeignException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
/** Twelve verbatim original send methods; UUID.randomUUID is unchanged. */
public final class F29LegacyNoteSendService {
''' + service_logger + ORIGINAL_SEOUL['verbatim_utf8'] + '''    private final PrivateNoteRepository privateNoteRepository;
    private final UserServiceClient userServiceClient;
    private final PrivateNoteStream privateNoteStream;
    private final Clock clock;
    private final TransactionOperations transactions;
    public F29LegacyNoteSendService(PrivateNoteRepository repository, UserServiceClient client,
            TransactionOperations transactions, Clock clock, PrivateNoteStream stream) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
        this.transactions = transactions;
        this.clock = clock;
        this.privateNoteStream = stream;
    }
''' + '\n'.join(methods) + '}\n'
    controller = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Receipt;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
''' + annotations + '''public final class F29LegacyNoteSendController {
    private final F29LegacyNoteSendService privateNoteService;
    public F29LegacyNoteSendController(F29LegacyNoteSendService service) {
        this.privateNoteService = service;
    }
''' + '\n'.join(controller_methods) + '}\n'
    advice = texts[ADVICE].replace(advice_annotations, F29_ADVICE_ANNOTATIONS, 1).replace(
            'public class PrivateNoteExceptionAdvice {', 'public class F29LegacyNoteSendExceptionAdvice {', 1)
    if advice.count(logger) != 1 or any(advice.count(method) != 1 for method in advice_methods):
        raise SystemExit('F29 advice changed an original handler/logger')
    if any(service.count(method) != 1 for method in methods) or any(controller.count(method) != 1 for method in controller_methods):
        raise SystemExit('F29 selected method bytes or LF changed')
    generated = dict(zip(OUTPUTS, (service.encode(), controller.encode(), advice.encode())))
    reject_duplicate_fqns(repository, output)
    for unit in (*SERVICE_UNITS, *CONTROLLER_UNITS, *ADVICE_UNITS):
        entries.append(unit['sha256'] + '  wrapper-method ' + unit['signature'].strip())
    for unit in (SERVICE_CONSTRUCTOR_UNIT, *SOURCE_EVIDENCE_UNITS):
        entries.append(unit['sha256'] + '  source-evidence-method ' + unit['signature'].strip())
    for label, unit in [('original-TX-field', ORIGINAL_TRANSACTION_FIELD), ('original-TT15', TRANSACTION_CONFIGURATION),
                        ('original-Clock-field', ORIGINAL_CLOCK_FIELD), ('original-Clock-assignment', ORIGINAL_CLOCK_ASSIGNMENT),
                        ('original-Stream-field', ORIGINAL_STREAM_FIELD), ('original-service-logger', SERVICE_LOGGER), ('original-Seoul-constant', ORIGINAL_SEOUL),
                        ('controller-annotations', CONTROLLER_ANNOTATIONS), ('advice-annotations', ADVICE_ANNOTATIONS), ('advice-logger', LOGGER)]:
        entries.append(unit['sha256'] + '  selected-unit ' + label)
    entries.extend([
        'wiring: unique F29 service/controller/advice; TransactionOperations+Clock+Stream constructor injection; original TT15/full constructor are source-only evidence',
        'wiring: original service logger class literal replaced once by canonical FQN String; original afterCommit body/type-only warning and logger category retained; no whole Service/stub',
        'wiring: raw original PrivateNoteStream is reused byte-for-byte from F26 shared output; it is never generated or auto-registered here',
        'proof: wrapper18 exact UTF-8/LF methods; extra10 source-only methods are evidence, not whole-service execution',
        'proof: real PG reference requires JpaTM and independent bounded User thread; RecordingTM/mockStream MVC is not PG/JPA/SSE proof',
        'proof: afterCommit may have bound Spring resources/ActualTxActive; actual committed visibility determines commit timing',
        'scope: pre-TX validation/hash, two active members, request/replay before block/rate, new Clock1 or reply Clock2, unchanged UUID.randomUUID, note/mailbox/request INSERT then recipient afterCommit publish',
        'scope: F30 source void expire samples the same Clock once inside TT15 and reuses raw Repository.expire; no new full source or wrapper type',
        'scope: no original app, cleanup scheduler, migration, JVM, PG or Stream thread starts during extraction',
    ])
    pending = [(output / relative, content) for relative, content in generated.items()]
    for relative, content in generated.items():
        entries.append(sha256(content) + '  generated ' + relative)
    pending.append((evidence, ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode()))
    for path, content in pending:
        require_plain_path(path, 'output or receipt')
        if path.exists() and (not path.is_file() or path.read_bytes() != content):
            raise SystemExit('Refusing differing F29 output or receipt')
    # Every full/shared/unit/FQN/output INCLUDING receipt passes before any mkdir/write.
    for path, content in pending:
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print('Verified F29 full22/shared14; selected wrappers3 and receipt; runtime not started')


# Explicit late opt-in: default F29/F30 full22, outputs3 and receipt remain unchanged.
MEMBER_RAW = ('user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberDeletion.java',
 'user-service/src/main/java/com/pawbridge/userservice/contact/CommunityContactClient.java',
 'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatModels.java',
 'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatRepository.java',
 'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatDataLifecycle.java')
MEMBER_SHARED = {'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/Role.java',
 'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/entity/User.java',
 'user-service/src/main/java/com/pawbridge/userservice/exception/UserNotFoundException.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/exception/UserNotFoundException.java',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ApplicationException.java',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'backend-app/boot/build/generated/sources/f3LegacyOracle/java/com/pawbridge/userservice/exception/common/ErrorCode.java',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java': 'backend-app/boot/build/generated/sources/f14LegacyOracle/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java',
 'user-service/src/main/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java',
 'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': 'backend-app/boot/build/generated/sources/f13LegacyOracle/java/com/pawbridge/userservice/repository/UserRepository.java',
 'user-service/src/main/java/com/pawbridge/userservice/util/ResponseDTO.java': 'backend-app/boot/build/generated/sources/f14LegacyOracle/java/com/pawbridge/userservice/util/ResponseDTO.java'}
MEMBER_INPUTS = {'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatDataLifecycle.java': '7e83ebf5667a3d92371ac5bf2269195163e17b8e35a5130a408348410507a512',
 'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatModels.java': '4910f09e9b64cbc24ded0a089df7efef46d8a46acec1677554b08f096a221a72',
 'community-service/src/main/java/com/pawbridge/communityservice/chat/MemberChatRepository.java': '942116aac4ae0699dd683dfd3ea13811158f8ead1e4fe3332a325de2f34c882d',
 'community-service/src/migration/resources/db/postgresql/V10__member_chat.sql': '7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb',
 'user-service/src/main/java/com/pawbridge/userservice/contact/CommunityContactClient.java': '91aedc9424a072bf0e3399deac0f325ec17714d442fc8e938b73bc1fc924311e',
 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberDeletion.java': 'b099a74a9f394581df50ff5092ad36bc73d1fe890546a35abf8e09e25f640e77',
 'user-service/src/main/java/com/pawbridge/userservice/entity/Role.java': '14eb5ed4e263777a06d6e127f650a78a03932c9d4d5020ae8f6939f756679c05',
 'user-service/src/main/java/com/pawbridge/userservice/entity/User.java': 'f2774b98a504ee58b166f251de938e0e6dd95b9e9f5e22e9474bf63f649de31e',
 'user-service/src/main/java/com/pawbridge/userservice/exception/UserNotFoundException.java': 'ddea3a2a8fb4d48cf31a719f6ee507c427df841d9d6f40778dceaa8a3bc747da',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ApplicationException.java': 'cc92224c6cec111f3f008ff14da267154dc587987ce5f86be169e7ce9a1ea727',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/ErrorCode.java': 'd2fcd341dd3bf2f0e65a0a4a2d2ebf3885c0e0354926da46d83b784e22fa9e4b',
 'user-service/src/main/java/com/pawbridge/userservice/exception/common/GlobalExceptionRestAdvice.java': '572380180b5eeae7ff1f5cd81819ae0a2eed1b0f99beb279d333b56d0ea3f97b',
 'user-service/src/main/java/com/pawbridge/userservice/persistence/PostgresqlRollbackCharsetViolation.java': '09ab66f94a5b980549a2153db799c00e6dbc11943b76c0af2cd64f1ef0995891',
 'user-service/src/main/java/com/pawbridge/userservice/repository/UserRepository.java': '09e803887a6386083bc50b06ebb2da5110ad8541bf7726c2440db3d843d2d915',
 'user-service/src/main/java/com/pawbridge/userservice/util/ResponseDTO.java': '107b22c2ac80290b8b61165fb2f8c35d7bf4a2c9b700e0a7f1a60b0501707fd3',
 'user-service/src/migration/resources/db/postgresql/V1__user_schema.sql': '5b6c50062231952e625e56a7577c41b03d7d5a10041380d4b2ada960ef7449a1',
 'user-service/src/migration/resources/db/postgresql/V2__rollback_charset_compatibility.sql': '2921e2a7b10c640ef24955902e38d649e5485af7df8656b10638ccf5f79521b8',
 'user-service/src/migration/resources/db/postgresql/V3__private_note_deletion_coordination.sql': '6ff3b954f5c16406a787229ee03b77e8e3fc4e562969782c1f32b9a2dae9bce1'}
MEMBER_UNITS = [{'end_delimiter': '\n    }\n',
  'sha256': '94e701269262c2ae4d0d7ee79f4648ae3d21bce3d639665366ed37f228ae5fc5',
  'signature': '    public void withdraw(long userId) {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    public void withdraw(long userId) {'},
 {'end_delimiter': '\n    }\n',
  'sha256': '9667ffbd6c876303de0f5b02929aed4ab5dc3d4ce50c737e464e62d292379bbf',
  'signature': '    private ContactMember lookup(long userId) {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    private ContactMember lookup(long userId) {'},
 {'end_delimiter': '\n    }\n',
  'sha256': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc',
  'signature': '    private static ResponseStatusException memberLookupUnavailable() {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    private static ResponseStatusException memberLookupUnavailable() {'},
 {'end_delimiter': '\n    }\n',
  'sha256': '700eb05797bbc27e4c3b531baae655d696c7b55d2d7afc8247c972f68f0a389f',
  'signature': '    private static void afterCommit(Runnable action) {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    private static void afterCommit(Runnable action) {'},
 {'end_delimiter': '\n    }\n',
  'sha256': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031',
  'signature': '    private static void requireId(long userId) {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    private static void requireId(long userId) {'},
 {'end_delimiter': '\n    }\n',
  'sha256': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad',
  'signature': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'start_anchor': '    private static ResponseStatusException error(HttpStatus status, String message) {'}]
MEMBER_EVIDENCE = 'backend-app/build/ci/member-cleanup-oracle-sha256.txt'
MEMBER_WRAPPER = 'com/pawbridge/communityservice/contact/F29LegacyMemberCleanupService.java'


def extract_member_cleanup(repository, output, evidence):
    """Only the tagged late PG task consumes these additional raw types/withdraw wrapper."""
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(p).absolute() for p in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / MEMBER_EVIDENCE:
        raise SystemExit('Refusing non-dedicated member-cleanup output/receipt')
    require_plain_path(output, 'member cleanup output')
    require_plain_path(evidence, 'member cleanup receipt')
    source, entries = {}, []
    for path, digest in {**EXPECTED, **MEMBER_INPUTS}.items():
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=repository)
        if sha256(raw) != digest:
            raise SystemExit('Member cleanup fixed source checksum differs: ' + path)
        source[path] = raw
        entries.append(digest + '  source ' + path)
    for path, relative in {**SHARED, **MEMBER_SHARED}.items():
        target = repository / relative
        require_plain_path(target, 'member cleanup raw shared input')
        if not target.is_file() or target.read_bytes() != source[path]:
            raise SystemExit('Member cleanup shared raw bytes differ: ' + path)
        entries.append(sha256(source[path]) + '  shared-byte-identity ' + relative)
    # prepareF29LegacyOracle is a hard task prerequisite. Require, never synthesize,
    # its three existing outputs/receipt here; the ordinary receipt stays byte-identical.
    base_receipt = repository / EVIDENCE_PATH
    require_plain_path(base_receipt, 'base F29 receipt')
    if not base_receipt.is_file():
        raise SystemExit('Member cleanup requires the existing F29 source receipt')
    base_text = base_receipt.read_text()
    for path in EXPECTED:
        if (EXPECTED[path] + '  source ' + path + '\n') not in base_text:
            raise SystemExit('Member cleanup base source receipt is incomplete')
    for relative in OUTPUTS:
        target = output / relative
        require_plain_path(target, 'base F29 generated output')
        if not target.is_file() or (sha256(target.read_bytes()) + '  generated ' + relative + '\n') not in base_text:
            raise SystemExit('Member cleanup base generated bytes/receipt differ')
    entries.append(sha256(base_receipt.read_bytes()) + '  base-F29-receipt ' + EVIDENCE_PATH)
    methods = [checked_method(source[SERVICE].decode('utf-8'), unit) for unit in MEMBER_UNITS]
    logger = checked_literal(source[SERVICE].decode('utf-8'), SERVICE_LOGGER).replace(
            'PrivateNoteService.class', '"com.pawbridge.communityservice.contact.PrivateNoteService"')
    wrapper = 'package com.pawbridge.communityservice.contact;\nimport com.pawbridge.communityservice.client.UserServiceClient;\nimport com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;\nimport com.pawbridge.communityservice.chat.MemberChatModels.Withdrawn;\nimport feign.FeignException;\nimport org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\nimport org.springframework.context.ApplicationEventPublisher;\nimport org.springframework.http.HttpStatus;\nimport org.springframework.transaction.support.TransactionOperations;\nimport org.springframework.transaction.support.TransactionSynchronization;\nimport org.springframework.transaction.support.TransactionSynchronizationManager;\nimport org.springframework.web.server.ResponseStatusException;\n/** Only six exact original methods; HTTP/Gateway, other note APIs and scheduling are not registered. */\npublic final class F29LegacyMemberCleanupService {\n' + logger + '    private final PrivateNoteRepository privateNoteRepository;\n    private final UserServiceClient userServiceClient;\n    private final TransactionOperations transactions;\n    private final PrivateNoteStream privateNoteStream;\n    private final ApplicationEventPublisher events;\n    public F29LegacyMemberCleanupService(PrivateNoteRepository repository, UserServiceClient client,\n            TransactionOperations transaction, PrivateNoteStream stream, ApplicationEventPublisher events) {\n        this.privateNoteRepository = repository;\n        this.userServiceClient = client;\n        this.transactions = transaction;\n        this.privateNoteStream = stream;\n        this.events = events;\n    }\n' + '\n'.join(methods) + '}\n'
    generated = {path.split('/src/main/java/', 1)[1]: source[path] for path in MEMBER_RAW}
    generated[MEMBER_WRAPPER] = wrapper.encode()
    for relative in generated:
        package, name = relative.rsplit('/', 1)
        for other in (repository / 'backend-app/boot').glob('**/*.java'):
            if other == output / relative:
                continue
            text = other.read_text()
            if ('package ' + package.replace('/', '.') + ';') in text and re.search(
                    r'\b(?:class|interface|record|enum)\s+' + re.escape(name[:-5]) + r'\b', text):
                raise SystemExit('Refusing duplicate member cleanup FQN')
    for unit in MEMBER_UNITS:
        entries.append(unit['sha256'] + '  wrapper-method ' + unit['signature'].strip())
    entries += ['wiring: raw User ContactMemberDeletion and raw chat lifecycle/models/repository, six exact withdraw methods only',
                'wiring: source User actual JpaTM and inherited SimpleJpaRepository deleteById/flush are test-owned wiring; not whole UserService or remote HTTP',
                'wiring: synchronous Spring EventListener under the same original Community JpaTM; no scheduled processor/Redis/stream resource starts',
                'scope: default F29/F30 closure22/generated3/evidence are unchanged; member cleanup is late opt-in only']
    for relative, raw in generated.items():
        entries.append(sha256(raw) + '  generated ' + relative)
    pending = [(output / relative, raw) for relative, raw in generated.items()]
    pending.append((evidence, ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode()))
    # Refuse ALL generated/source/receipt conflicts before any mkdir or write.
    for target, raw in pending:
        require_plain_path(target, 'member cleanup output or receipt')
        if target.exists() and (not target.is_file() or target.read_bytes() != raw):
            raise SystemExit('Refusing differing member cleanup output or receipt')
    for target, raw in pending:
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)

if __name__ == '__main__':
    if len(sys.argv) == 5 and sys.argv[1] == '--member-cleanup':
        extract_member_cleanup(*sys.argv[2:])
    elif len(sys.argv) == 4:
        extract(*sys.argv[1:])
    else:
        raise SystemExit('F29 needs [--member-cleanup] repository, generated source directory and evidence')
