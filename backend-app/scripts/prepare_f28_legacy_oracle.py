#!/usr/bin/env python3
"""F28 selected-original deletion: prepare only; shared F26 stream is immutable input."""
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
 'verbatim_utf8': '    private static final Logger log = LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class);\n',
 'sha256': 'a5f9f551b1e478d7086f34f253b40b39256b118ad2db7e81e90e6e25dd9eab77'}

MODEL_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record ContactMember(Long userId, String nickname, boolean active, boolean '
                   'deletionPending) {}\n',
  'sha256': 'c037d57bad5a40e651d625333788ff4267b5803b84825fdfc006943e885a6448'}]

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
  'sha256': '0a794d8711da88e4c98ed98fe931d2b775dd29037169cd9c1655a34ad91d83bc'}]

INDEX_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_rate ON private_note_requests (sender_id, created_at '
                   'DESC);\n',
  'sha256': 'a7744fbfb946dfb2e7b42168a4dbf99a52bec4978d88dea24125007495b5822f'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_note ON private_note_requests (note_id) WHERE '
                   'note_id IS NOT NULL;\n',
  'sha256': '65a253fa5f95bd592ff66373daf3fd7d41614c8c9a1aeaed5f6a6db071036fbc'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_request_cleanup ON private_note_requests (created_at) WHERE '
                   'note_id IS NULL;\n',
  'sha256': '020b3e8baa54267e0699f878ccabd5cfbb68d35ff187d381491910315ec16ab0'}]

F18_ADAPTED_PATH = 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

F18_ADAPTED_SHA256 = 'fc02a8aefbe87a5f9b3e7d3017334d0b38d1fb7fe5b499cc080528e6cc1a03bf'

F18_ADAPTED_ANNOTATIONS = '@Order(Ordered.HIGHEST_PRECEDENCE)\n@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n'

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
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java': '3215dd7b9f3958049ea3065bc550883838bfeacc46d75e350cbc29f26a9504eb'}

STREAM = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java'

SERVICE_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    public void delete(long currentUserId, UUID noteId) {',
  'start_anchor': '    public void delete(long currentUserId, UUID noteId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '0e1cfd3f629c6fcb892ac86decdc077367b9d4b6f4b72cd8aaabad1d310d6a7b'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private void mutate(long currentUserId, UUID noteId, Runnable change) {',
  'start_anchor': '    private void mutate(long currentUserId, UUID noteId, Runnable change) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'e598fd6e0d7609085eacb364679337b0b582b04c1d28612e4cf8f15896de306a'},
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

CONTROLLER_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
  'signature': '    public void delete(',
  'start_anchor': '    @DeleteMapping("/{id}")\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'e61b9aac65b02cc15daebd188c4238c26504631a8337f19066294a9c1b2469b6'}]

SOURCE_EVIDENCE_UNITS = [{'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    public ContactMember get(long userId) {',
  'start_anchor': '    public ContactMember get(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9d17e0ed651f3f48437962a87126bf14fb149a8a8eb038a191ad37d792687619'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void lockMembers(long firstMemberId, long secondMemberId) {',
  'start_anchor': '    /** Uses the same member lock order for sending, blocking and withdrawal. */\n',
  'end_delimiter': '\n    }\n',
  'sha256': '61691e2a9c58387e66ff7761d6bbe00e858ccbd01343de5a9169e9ab04ebbe3a'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public Optional<Note> find(long ownerId, UUID noteId, Instant now) {',
  'start_anchor': '    public Optional<Note> find(long ownerId, UUID noteId, Instant now) {',
  'end_delimiter': '\n    }\n',
  'sha256': '13a97b5340ffde6321705796190837e63cda3e98ca5f63b1ac0357dd59b3d39c'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void lockNote(UUID noteId) {',
  'start_anchor': '    public void lockNote(UUID noteId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'bb4695fef3928cb1afa3456141d4d97528e0b26a0b8c2fb7bda7e30138751001'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void delete(long ownerId, UUID noteId) {',
  'start_anchor': '    public void delete(long ownerId, UUID noteId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'ad53cc9ca1e4efc022df6b0598e7941a17e5c0340afab62110f58ea1ba3ec769'}]

OUTPUT_ROOT = 'backend-app/boot/build/generated/sources/f28LegacyOracle/java'

EVIDENCE_PATH = 'backend-app/build/ci/f28-oracle-sha256.txt'

OUTPUTS = ('com/pawbridge/communityservice/contact/F28LegacyNoteDeletionService.java',
 'com/pawbridge/communityservice/contact/F28LegacyNoteDeletionController.java',
 'com/pawbridge/communityservice/contact/F28LegacyNoteDeletionExceptionAdvice.java')

F28_ADVICE_ANNOTATIONS = '@Order(Ordered.HIGHEST_PRECEDENCE)\n@RestControllerAdvice(assignableTypes = F28LegacyNoteDeletionController.class)\n'

ORIGINAL_STREAM_FIELD = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private final PrivateNoteStream privateNoteStream;\n',
 'sha256': 'e526e0680d0536ba2764601a8dfe5a6ebf1ba2c148e1055954b392e8907fbacc'}

SERVICE_LOGGER = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private static final Logger log = LoggerFactory.getLogger(PrivateNoteService.class);\n',
 'sha256': 'e5340b4a475a1810388bc8ff1df13c67df3a28cbe042ef97b343f03dc21b96a5'}

def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_method(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F28 selected method/annotation is not unique: ' + signature.strip())
    begin = text.index(anchor)
    try:
        signature_at = text.index(signature, begin)
        finish = text.index(ending, signature_at) + len(ending)
    except ValueError:
        raise SystemExit('F28 selected method boundary differs: ' + signature.strip()) from None
    selected = text[begin:finish]
    if not selected.endswith('}\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F28 selected method/LF checksum mismatch: ' + signature.strip())
    return selected


def checked_section(text, specification):
    begin, ending = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(ending) != 1:
        raise SystemExit('F28 selected class annotation boundaries are not unique')
    first, last = text.index(begin), text.index(ending)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F28 selected class annotation/LF checksum mismatch')
    return selected


def checked_literal(text, specification):
    selected = specification['verbatim_utf8']
    if (text.count(selected) != 1 or not selected.endswith('\n')
            or sha256(selected.encode('utf-8')) != specification['sha256']):
        raise SystemExit('F28 selected constant/model/logger literal or LF differs')
    return selected


def checked_fragment(text, specification):
    anchor, ending = specification['start_anchor'], specification['end_delimiter']
    if text.count(anchor) != 1:
        raise SystemExit('F28 source-only DDL fragment is not unique')
    first = text.index(anchor)
    try:
        last = text.index(ending, first) + len(ending)
    except ValueError:
        raise SystemExit('F28 source-only DDL fragment boundary differs') from None
    selected = text[first:last]
    if not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F28 source-only DDL fragment/LF checksum mismatch')
    return selected


def require_plain_path(path, label):
    if path.is_symlink() or path.resolve() != path:
        raise SystemExit('Refusing redirected F28 ' + label)
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise SystemExit('Refusing non-directory parent of F28 ' + label)


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
                    raise SystemExit('Refusing duplicate F28 wrapper FQN: ' + wanted_package + '.' + name)


def extract(repository, output, evidence):
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(value).absolute() for value in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / EVIDENCE_PATH:
        raise SystemExit('Refusing non-dedicated F28 output')
    require_plain_path(output, 'oracle output')
    require_plain_path(evidence, 'oracle evidence')
    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if sha256(content) != expected:
            raise SystemExit('Legacy F28 full input checksum differs: ' + source)
        inputs[source] = content
        entries.append(expected + '  source ' + source)
    for source, relative in SHARED.items():
        target = repository / relative
        require_plain_path(target, 'shared source')
        if not target.is_file() or target.read_bytes() != inputs[source]:
            raise SystemExit('F28 shared raw bytes differ: ' + source)
        entries.append(EXPECTED[source] + '  shared-byte-identity ' + relative)
    texts = {source: content.decode('utf-8') for source, content in inputs.items()}
    methods = [checked_method(texts[SERVICE], unit) for unit in SERVICE_UNITS]
    controller_methods = [checked_method(texts[CONTROLLER], unit) for unit in CONTROLLER_UNITS]
    checked_method(texts[SERVICE], SERVICE_CONSTRUCTOR_UNIT)
    for unit in (ORIGINAL_TRANSACTION_FIELD, ORIGINAL_CLOCK_FIELD, ORIGINAL_CLOCK_ASSIGNMENT,
                 ORIGINAL_STREAM_FIELD, TRANSACTION_CONFIGURATION, SERVICE_LOGGER):
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
        raise SystemExit('F28 adapted shared F18 advice differs')
    entries.append(F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + F18_ADAPTED_PATH)
    service_logger = SERVICE_LOGGER['verbatim_utf8'].replace('PrivateNoteService.class', '"com.pawbridge.communityservice.contact.PrivateNoteService"')
    service = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Note;
import feign.FeignException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
/** Nine verbatim original methods; caller supplies original-manager TT15 and explicit Clock/Stream. */
public final class F28LegacyNoteDeletionService {
''' + service_logger + '''    private final PrivateNoteRepository privateNoteRepository;
    private final UserServiceClient userServiceClient;
    private final PrivateNoteStream privateNoteStream;
    private final Clock clock;
    private final TransactionOperations transactions;
    public F28LegacyNoteDeletionService(PrivateNoteRepository repository, UserServiceClient client,
            TransactionOperations transactions, Clock clock, PrivateNoteStream stream) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
        this.transactions = transactions;
        this.clock = clock;
        this.privateNoteStream = stream;
    }
''' + '\n'.join(methods) + '}\n'
    controller = '''package com.pawbridge.communityservice.contact;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
''' + annotations + '''public final class F28LegacyNoteDeletionController {
    private final F28LegacyNoteDeletionService privateNoteService;
    public F28LegacyNoteDeletionController(F28LegacyNoteDeletionService service) {
        this.privateNoteService = service;
    }
''' + '\n'.join(controller_methods) + '}\n'
    advice = texts[ADVICE].replace(advice_annotations, F28_ADVICE_ANNOTATIONS, 1).replace(
            'public class PrivateNoteExceptionAdvice {', 'public class F28LegacyNoteDeletionExceptionAdvice {', 1)
    if advice.count(logger) != 1 or any(advice.count(method) != 1 for method in advice_methods):
        raise SystemExit('F28 advice changed an original handler/logger')
    if any(service.count(method) != 1 for method in methods) or any(controller.count(method) != 1 for method in controller_methods):
        raise SystemExit('F28 selected method bytes or LF changed')
    generated = dict(zip(OUTPUTS, (service.encode(), controller.encode(), advice.encode())))
    reject_duplicate_fqns(repository, output)
    for unit in (*SERVICE_UNITS, *CONTROLLER_UNITS, *ADVICE_UNITS):
        entries.append(unit['sha256'] + '  wrapper-method ' + unit['signature'].strip())
    for unit in (SERVICE_CONSTRUCTOR_UNIT, *SOURCE_EVIDENCE_UNITS):
        entries.append(unit['sha256'] + '  source-evidence-method ' + unit['signature'].strip())
    for label, unit in [('original-TX-field', ORIGINAL_TRANSACTION_FIELD), ('original-TT15', TRANSACTION_CONFIGURATION),
                        ('original-Clock-field', ORIGINAL_CLOCK_FIELD), ('original-Clock-assignment', ORIGINAL_CLOCK_ASSIGNMENT),
                        ('original-Stream-field', ORIGINAL_STREAM_FIELD), ('original-service-logger', SERVICE_LOGGER),
                        ('controller-annotations', CONTROLLER_ANNOTATIONS), ('advice-annotations', ADVICE_ANNOTATIONS), ('advice-logger', LOGGER)]:
        entries.append(unit['sha256'] + '  selected-unit ' + label)
    entries.extend([
        'wiring: unique F28 service/controller/advice; TransactionOperations+Clock+Stream constructor injection; original TT15/full constructor are source-only evidence',
        'wiring: original service logger class literal replaced once by canonical FQN String; original afterCommit body/type-only warning and logger category retained; no whole Service/stub',
        'wiring: raw original PrivateNoteStream is reused byte-for-byte from F26 shared output; it is never generated or auto-registered here',
        'proof: wrapper14 exact UTF-8/LF methods; extra6 source-only methods are evidence, not whole-service execution',
        'proof: real PG reference requires JpaTM and independent bounded User thread; RecordingTM/mockStream MVC is not PG/JPA/SSE proof',
        'proof: afterCommit may have bound Spring resources/ActualTxActive; actual committed visibility determines commit timing',
        'scope: deletion samples Clock twice; exact own-mailbox DELETE then conditional orphan-note DELETE; FK side effects are PG evidence; no affected-row policy',
        'scope: no original app, cleanup scheduler, migration, JVM, PG or Stream thread starts during extraction',
    ])
    pending = [(output / relative, content) for relative, content in generated.items()]
    for relative, content in generated.items():
        entries.append(sha256(content) + '  generated ' + relative)
    pending.append((evidence, ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode()))
    for path, content in pending:
        require_plain_path(path, 'output or receipt')
        if path.exists() and (not path.is_file() or path.read_bytes() != content):
            raise SystemExit('Refusing differing F28 output or receipt')
    # Every full/shared/unit/FQN/output INCLUDING receipt passes before any mkdir/write.
    for path, content in pending:
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print('Verified F28 full20/shared14; selected wrappers3 and receipt; runtime not started')

if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F28 needs repository, generated source directory and evidence')
    extract(*sys.argv[1:])
