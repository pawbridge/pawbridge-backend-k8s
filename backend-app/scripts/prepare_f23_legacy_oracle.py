#!/usr/bin/env python3
"""Pin F23 private-note list sources; generate three unique selected-original test wrappers.

Shared raw sources and F18 adapted advice are read-only inputs. No lifecycle,
SSE, HTTP/Feign transport, original app or transaction proxy is started.
"""
import hashlib
import pathlib
import re
import subprocess
import sys

SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'

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
 'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql': 'd2572afc2644df3f932872bcd757e47937cdf7d5b89789439cb103de6c71512d'}

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
 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java': 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java'}

SERVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java'

CONTROLLER = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java'

ADVICE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

SERVICE_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    public NotePage list(long currentUserId, String box, boolean favorites, int page) {',
  'start_anchor': '    public NotePage list(long currentUserId, String box, boolean favorites, int page) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'cf5c6ea2d33841857fa442b719d3f2e2005c603ceaf1f12b586c2cf74c52758a'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private Long counterpartId(Note note) {',
  'start_anchor': '    private Long counterpartId(Note note) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'aeac8c818abaaea87d808c7bb58724ae1978498980e667ac5790c67260f774a0'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private NoteView view(Note note, ContactMember counterpart) {',
  'start_anchor': '    private NoteView view(Note note, ContactMember counterpart) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'bfb6ed0f914783a5dc62d89410a8a1cc7c7ed405cc594d90ac81d8fdafa52694'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private ContactMember display(Long userId, Map<Long, ContactMember> membersById) {',
  'start_anchor': '    private ContactMember display(Long userId, Map<Long, ContactMember> membersById) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'b5e1c6b1708481d101b4a9997ef084404250fdc542a96109003e3d769847ddd6'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private ContactMember display(ContactMember member) {',
  'start_anchor': '    private ContactMember display(ContactMember member) {',
  'end_delimiter': '\n    }\n',
  'sha256': '2a5955cf23d03416a2483e8b909908adb1a05d7a345bd7072d57eb84b6fde96d'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private ContactMember withdrawnMember() {',
  'start_anchor': '    private ContactMember withdrawnMember() {',
  'end_delimiter': '\n    }\n',
  'sha256': '7dc400bb7df18bb0fe7b51d518231374c9ad6369fa3bb28a89ef0e7ce59da941'},
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
  'signature': '    private Map<Long, ContactMember> lookupMembers(List<Long> memberIds) {',
  'start_anchor': '    private Map<Long, ContactMember> lookupMembers(List<Long> memberIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': '2a2a452ffe70ea20a7d6a9d6f63a4588b44ec117a8d5bedbd6890b1079fc6601'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException memberLookupUnavailable() {',
  'start_anchor': '    private static ResponseStatusException memberLookupUnavailable() {',
  'end_delimiter': '\n    }\n',
  'sha256': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static void requirePage(int page) {',
  'start_anchor': '    private static void requirePage(int page) {',
  'end_delimiter': '\n    }\n',
  'sha256': '0ee3fa29b4942ce4159d290273e492350bb67ce7c552f404302da6f9682d6f05'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static int totalPages(long count) {',
  'start_anchor': '    private static int totalPages(long count) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9c2ff38acf26cef059215d29d9975b5d84ac93f6c98c49d878e8432000557363'},
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


PAGE_SIZE = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private static final int NOTE_PAGE_SIZE = 10;\n',
 'sha256': 'b1b85fa04e4233bb974026a296eddcaeb203c8f4e1e40f1e55ca18a7632fc44a'}

CONTROLLER_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
 'signature': '    public ResponseDTO<NotePage> list(',
 'start_anchor': '    @GetMapping\n',
 'end_delimiter': '\n    }\n',
 'sha256': 'ba928e2c772cdac1e483991931b36723450e28e753152c093fb9f76688a38d80'}

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

SOURCE_EVIDENCE_UNITS = [{'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    public ContactMember get(long userId) {',
  'start_anchor': '    public ContactMember get(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9d17e0ed651f3f48437962a87126bf14fb149a8a8eb038a191ad37d792687619'},
 {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    public List<ContactMember> getAll(List<Long> userIds) {',
  'start_anchor': '    public List<ContactMember> getAll(List<Long> userIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': '1a88751e48f0e2b5bd3a85177984ab57cdb6b4a546e2fb0d762f08b0391abe73'},
 {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    private static void validateIds(List<Long> userIds) {',
  'start_anchor': '    private static void validateIds(List<Long> userIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'f2ae79e5c62e4e13d4e87b673d12b68be14e07ec7501132996055d3fb7c42c56'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public long count(long ownerId, String box, boolean favoritesOnly, Instant now) {',
  'start_anchor': '    public long count(long ownerId, String box, boolean favoritesOnly, Instant now) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'f8fd4e8a11a8dde71c906504f0e5035bf4770de5df460f5b4d73cd61323c22c2'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public List<Note> list(long ownerId, String box, boolean favoritesOnly, int page, '
               'Instant now) {',
  'start_anchor': '    public List<Note> list(long ownerId, String box, boolean favoritesOnly, int page, '
                  'Instant now) {',
  'end_delimiter': '\n    }\n',
  'sha256': '4248dea66fc40dee6582d43aa3d7df2048a0f6365be944d63fdb53e6fe7b833e'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public boolean blocked(long firstMemberId, long secondMemberId) {',
  'start_anchor': '    public boolean blocked(long firstMemberId, long secondMemberId) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'fd399232b50c0b6b96305d10a3ab5262eb352d6f9fbefb711b73d3f87d5b73ac'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    private static Note mapNote(ResultSet row, int rowNumber) throws SQLException {',
  'start_anchor': '    private static Note mapNote(ResultSet row, int rowNumber) throws SQLException {',
  'end_delimiter': '\n    }\n',
  'sha256': '75b5385f439d2f8919532c61ac77ed105c98183cb9c978766164a75119fdf0e3'},
 {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberController.java',
  'signature': '    public List<ContactMember> getAll(@RequestParam("ids") List<Long> userIds) {',
  'start_anchor': '    @GetMapping("/contacts")\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'de994ade4c6328b0e51084c8808a77a2e2cac5759165eb43a9a580d49d9c99a2'}]

USER_LIMIT = {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
 'verbatim_utf8': '    private static final int MAX_MEMBERS_PER_REQUEST = 21;\n',
 'sha256': '7e531652b929cfdfb54fce449c653e9cddf68d71c217d8f8680cfc4c0416f026'}

USER_CONTROLLER_ANNOTATIONS = {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberController.java',
 'start_anchor': '@RestController\n',
 'end_exclusive_anchor': 'public class ContactMemberController {',
 'sha256': '974cf4b27a25f82b86f9297b7c8d6e8a9ec0149b00de4ffc057f9cfad15e1cd1'}

MODEL_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record Note(\n'
                   '            UUID noteId, Long senderId, Long recipientId, String body, UUID replyTo,\n'
                   '            String contextType, Long contextId, Instant createdAt, Instant expiresAt,\n'
                   '            String direction, Instant readAt, boolean favorite) {}\n',
  'sha256': '055a2cbe00ad59ca2258bd74b629e2a49ee60b52d21da202fe4b2aaf638162c0'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record NoteView(\n'
                   '            UUID noteId, String body, String direction, Instant createdAt,\n'
                   '            Instant readAt, boolean favorite, Long counterpartId, String '
                   'counterpartNickname,\n'
                   '            boolean canReply, String contextHref) {}\n',
  'sha256': '24fc331b89166326f651d32ccdd9d9449b5fa50e8add9dd547778db9fb0e3c68'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record NotePage(List<NoteView> content, long totalElements, int totalPages, '
                   'int number, int size) {}\n',
  'sha256': 'f8f7fec790e4c4a5899db064afb4ddfd6609dfed84ffccd51b174af723e47a96'}]

DDL_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_notes (',
  'end_delimiter': ');\n',
  'sha256': '4663bf9d3537c754a36c8ae014b802039767bcbf42e30868e8bade173143ae08'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_mailboxes (',
  'end_delimiter': ');\n',
  'sha256': '898a9dcfee52f958ed6a8f77fdd1960bd48562af3f33feea7b97a2fe0950ab4a'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_members (',
  'end_delimiter': ');\n',
  'sha256': '3013f85e076821cc380c7d8e3c4f750869a053b80deff0938613fd684d0c913e'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_blocks (',
  'end_delimiter': ');\n',
  'sha256': '02b79fe553ca9307b80422a9804652850914fd75b55516616dffaccd159bf9c7'}]

INDEX_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_notes_expiry ON private_notes (expires_at, note_id);\n',
  'sha256': 'c9286f374c45f4b6697dd0c86b5e6d57556dbc4195b8468cb57b748152a1a4ff'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_mailbox_page ON private_note_mailboxes (member_id, '
                   'direction, created_at DESC, note_id DESC);\n',
  'sha256': '4d177b47e945939bfe8ce642d47563c2e8ccfc53958f366d5c5f328ada1d31b9'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_mailbox_favorite ON private_note_mailboxes (member_id, '
                   'created_at DESC, note_id DESC) WHERE favorite;\n',
  'sha256': 'fb69c622112c7bf3b14b7ce2083e252114f01f4cf6f425b74c665827f3126954'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_mailbox_unread ON private_note_mailboxes (member_id, '
                   "created_at DESC, note_id DESC) WHERE direction = 'INBOX' AND read_at IS NULL;\n",
  'sha256': 'd75b4f227be9184740c84815bd803950d9e58996ade2f82c74a8537201ada6dc'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_mailbox_note ON private_note_mailboxes (note_id);\n',
  'sha256': '970d7712d38bf41052afe7f58b39a0945c8f60e35b64981604e6184422655889'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_sender ON private_notes (sender_id) WHERE sender_id IS NOT '
                   'NULL;\n',
  'sha256': '88dc9a0452ab694dc8d6b724f915ec6808f5b144a41f84822245123e98619ad0'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V7__private_note_cleanup_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_recipient ON private_notes (recipient_id) WHERE '
                   'recipient_id IS NOT NULL;\n',
  'sha256': '27e9519ea6967c0594f6b906fab87149654bf6573f3b2c3f6442cae91714dd48'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_reply_to ON private_notes (reply_to) WHERE reply_to IS NOT '
                   'NULL;\n',
  'sha256': 'adcacc4ddcfa2f6b30dbe289ca8e3ca46f83f761da23678d75ffcd55656365d4'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_blocked_member ON private_note_blocks (blocked_id);\n',
  'sha256': 'fa1e45bc305da331c2e478cd17ada3083ab3856bee7648df3f2ac2befe9576d4'}]

MAILBOX_JOIN_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
 'verbatim_utf8': '    private static final String MAILBOX_JOIN = """\n'
                  '             FROM pawbridge_community.private_note_mailboxes m\n'
                  '             JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id\n'
                  '            """;\n',
 'sha256': '630b568b32487067e4f608757e69b2286a198612626be2f57b4a0a11c7fcbcba'}

F18_ADAPTED_PATH = 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

F18_ADAPTED_SHA256 = 'fc02a8aefbe87a5f9b3e7d3017334d0b38d1fb7fe5b499cc080528e6cc1a03bf'

F18_ADAPTED_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n')

F23_ADVICE_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F23LegacyPrivateNoteListController.class)\n')

OUTPUT_ROOT = 'backend-app/boot/build/generated/sources/f23LegacyOracle/java'

EVIDENCE_PATH = 'backend-app/build/ci/f23-oracle-sha256.txt'

OUTPUTS = ('com/pawbridge/communityservice/contact/F23LegacyPrivateNoteListService.java',
 'com/pawbridge/communityservice/contact/F23LegacyPrivateNoteListController.java',
 'com/pawbridge/communityservice/contact/F23LegacyPrivateNoteListExceptionAdvice.java')


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_method(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F23 selected method/annotation is not unique: ' + signature.strip())
    begin = text.index(anchor)
    try:
        signature_at = text.index(signature, begin)
        finish = text.index(ending, signature_at) + len(ending)
    except ValueError:
        raise SystemExit('F23 selected method boundary differs: ' + signature.strip()) from None
    selected = text[begin:finish]
    if not selected.endswith('}\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F23 selected method/LF checksum mismatch: ' + signature.strip())
    return selected


def checked_section(text, specification):
    begin, ending = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(ending) != 1:
        raise SystemExit('F23 selected class annotation boundaries are not unique')
    first, last = text.index(begin), text.index(ending)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F23 selected class annotation/LF checksum mismatch')
    return selected


def checked_literal(text, specification):
    selected = specification['verbatim_utf8']
    if (text.count(selected) != 1 or not selected.endswith('\n')
            or sha256(selected.encode('utf-8')) != specification['sha256']):
        raise SystemExit('F23 selected constant/model/logger literal or LF differs')
    return selected


def checked_fragment(text, specification):
    anchor, ending = specification['start_anchor'], specification['end_delimiter']
    if text.count(anchor) != 1:
        raise SystemExit('F23 source-only DDL fragment is not unique')
    first = text.index(anchor)
    try:
        last = text.index(ending, first) + len(ending)
    except ValueError:
        raise SystemExit('F23 source-only DDL fragment boundary differs') from None
    selected = text[first:last]
    if not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F23 source-only DDL fragment/LF checksum mismatch')
    return selected


def require_plain_path(path, label):
    if path.is_symlink() or path.resolve() != path:
        raise SystemExit('Refusing redirected F23 ' + label)
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise SystemExit('Refusing non-directory parent of F23 ' + label)


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
                    raise SystemExit('Refusing duplicate F23 wrapper FQN: ' + wanted_package + '.' + name)


def extract(repository, output, evidence):
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(value).absolute() for value in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / EVIDENCE_PATH:
        raise SystemExit('Refusing non-dedicated F23 oracle output')
    # Validate destination redirection before Git reads and before any mkdir/write.
    require_plain_path(output, 'oracle output')
    require_plain_path(evidence, 'oracle evidence')

    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if sha256(content) != expected:
            raise SystemExit('Legacy F23 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  source ' + source)
    for source, relative in SHARED.items():
        generated = repository / relative
        require_plain_path(generated, 'shared source')
        if not generated.is_file() or generated.read_bytes() != inputs[source]:
            raise SystemExit('Shared generated bytes differ from fixed F23 source: ' + source)
        entries.append(EXPECTED[source] + '  shared-byte-identity ' + relative + ' <= ' + source)

    text = {source: content.decode('utf-8') for source, content in inputs.items()}
    service_methods = [checked_method(text[SERVICE], specification) for specification in SERVICE_UNITS]
    page_size = checked_literal(text[SERVICE], PAGE_SIZE)
    controller_annotations = checked_section(text[CONTROLLER], CONTROLLER_ANNOTATIONS)
    controller_method = checked_method(text[CONTROLLER], CONTROLLER_UNIT)
    advice_annotations = checked_section(text[ADVICE], ADVICE_ANNOTATIONS)
    advice_methods = [checked_method(text[ADVICE], specification) for specification in ADVICE_UNITS]
    logger = checked_literal(text[ADVICE], LOGGER)
    for specification in SOURCE_EVIDENCE_UNITS:
        checked_method(text[specification['source']], specification)
    checked_literal(text[MAILBOX_JOIN_UNIT['source']], MAILBOX_JOIN_UNIT)
    checked_literal(text[USER_LIMIT['source']], USER_LIMIT)
    checked_section(text[USER_CONTROLLER_ANNOTATIONS['source']], USER_CONTROLLER_ANNOTATIONS)
    for specification in MODEL_UNITS:
        checked_literal(text[specification['source']], specification)
    for specification in DDL_UNITS:
        checked_fragment(text[specification['source']], specification)
    for specification in INDEX_UNITS:
        checked_literal(text[specification['source']], specification)

    adapted_path = repository / F18_ADAPTED_PATH
    require_plain_path(adapted_path, 'adapted shared advice')
    if not adapted_path.is_file():
        raise SystemExit('F23 requires the existing F18 original-name adapted advice')
    adapted = adapted_path.read_bytes()
    expected_adapted = text[ADVICE].replace(advice_annotations, F18_ADAPTED_ANNOTATIONS, 1).encode('utf-8')
    if sha256(adapted) != F18_ADAPTED_SHA256 or adapted != expected_adapted:
        raise SystemExit('F18 adapted advice differs from the fixed scope-only F23 reference')
    if adapted.decode('utf-8').count(logger) != 1:
        raise SystemExit('F18 original-name logger wiring differs from the F23 reference')
    entries.append(F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + F18_ADAPTED_PATH)

    fields = ('    private final PrivateNoteRepository privateNoteRepository;\n',
              '    private final UserServiceClient userServiceClient;\n',
              '    private final Clock clock;\n')
    if any(text[SERVICE].count(field) != 1 for field in fields):
        raise SystemExit('F23 original service injection fields differ')
    if text[CONTROLLER].count('    private final PrivateNoteService privateNoteService;\n') != 1:
        raise SystemExit('F23 original controller injection field differs')
    original_declaration = 'public class PrivateNoteExceptionAdvice {'
    if text[ADVICE].count(original_declaration) != 1:
        raise SystemExit('F23 original advice class declaration differs')

    service_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NoteView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NotePage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.Note;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import feign.FeignException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Test-only wiring around fourteen unchanged original methods and NOTE_PAGE_SIZE=10; no original lifecycle or outer TX. */
public final class F23LegacyPrivateNoteListService {
''' + page_size + '\n' + ''.join(fields) + '''
    public F23LegacyPrivateNoteListService(PrivateNoteRepository repository, UserServiceClient client, Clock clock) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
        this.clock = clock;
    }
''' + '\n'.join(service_methods) + '}\n'
    controller_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NoteView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.NotePage;
import com.pawbridge.communityservice.util.ResponseDTO;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
''' + controller_annotations + '''public final class F23LegacyPrivateNoteListController {
    private final F23LegacyPrivateNoteListService privateNoteService;
    public F23LegacyPrivateNoteListController(F23LegacyPrivateNoteListService service) {
        this.privateNoteService = service;
    }
''' + controller_method + '}\n'
    advice_wrapper = text[ADVICE].replace(advice_annotations, F23_ADVICE_ANNOTATIONS, 1).replace(
        original_declaration, 'public class F23LegacyPrivateNoteListExceptionAdvice {', 1)
    # Preserve the original class literal: it now resolves to the reused F18 advice FQN.
    if advice_wrapper.count(logger) != 1 or any(method not in advice_wrapper for method in advice_methods):
        raise SystemExit('F23 advice adaptation changed a handler or the original-name logger literal')
    for method in service_methods:
        if service_wrapper.count(method) != 1:
            raise SystemExit('F23 wrapper did not retain an exact original service method')
    if controller_wrapper.count(controller_method) != 1:
        raise SystemExit('F23 wrapper did not retain the complete annotated controller method')
    generated = dict(zip(OUTPUTS, (content.encode('utf-8') for content in
                                 (service_wrapper, controller_wrapper, advice_wrapper))))
    reject_duplicate_fqns(repository, output)

    for specification in (*SERVICE_UNITS, CONTROLLER_UNIT, *ADVICE_UNITS):
        entries.append(specification['sha256'] + '  wrapper-method ' + specification['signature'].strip())
    for specification in SOURCE_EVIDENCE_UNITS:
        entries.append(specification['sha256'] + '  source-evidence-method ' + specification['signature'].strip())
    for label, specification in (('service-page-size', PAGE_SIZE), ('mailbox-join', MAILBOX_JOIN_UNIT), ('controller-class-annotations', CONTROLLER_ANNOTATIONS),
                                 ('original-advice-class-annotations', ADVICE_ANNOTATIONS), ('original-advice-logger', LOGGER),
                                 ('user-raw-batch-limit', USER_LIMIT), ('user-http-class-annotations', USER_CONTROLLER_ANNOTATIONS),
                                 ('note-record', MODEL_UNITS[0]), ('note-view-record', MODEL_UNITS[1]), ('note-page-record', MODEL_UNITS[2])):
        entries.append(specification['sha256'] + '  selected-unit ' + label)
    for ordinal, specification in enumerate(DDL_UNITS):
        entries.append(specification['sha256'] + '  source-only-ddl-fragment ' + str(ordinal) + ' ' + specification['start_anchor'])
    for specification in INDEX_UNITS:
        entries.append(specification['sha256'] + '  source-only-index ' + specification['verbatim_utf8'].strip())
    entries.extend((
        'wiring: unique F23 service/controller/advice classes; service retains only three original fields and an explicit constructor',
        'wiring: fourteen service methods and NOTE_PAGE_SIZE=10 are verbatim including closing LF; no outer transaction or lifecycle fields',
        'wiring: original class mapping and annotated list retain explicit box plus unnamed favorites/page query defaults verbatim; only injected service type/constructor change',
        'wiring: advice class name and assignableTypes alone adapt to F23; original imports, Order, four handlers and logger literal remain verbatim',
        'wiring: unchanged LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class) resolves to the existing F18 original-name adapted class; logger category remains original FQN',
        'wiring: do not register the F18 advice bean for F23 or broaden its scope; retain its already-built classpath closure',
        'proof: twelve raw shared FQNs and one F18 adapted advice are read-only reused inputs; no Boundary/DTO/Models/Repository/User source is copied',
        'proof: original User get/getAll/validate and Repository count/list/blocked/mapNote execute only when a separate harness calls the existing actual shared classes',
        'proof: internal User HTTP/getAll mapping and V6/V7/V8 TSTZ/PK/FK/CHECK/indexes are source evidence; no User HTTP, Feign transport or DDL is executed',
        'proof: service wrapper runs selected original methods only; no whole original PrivateNoteService, transaction proxy, app, SSE, chat or lifecycle execution',
        'scope: extraction starts no JVM/PG/HTTP/socket or scheduling; separate runtime/MVC/PG evidence is required',
        'proof: original owner-reference n.* is not candidate restricted ACL proof; no reply_to grant is implied',
        'proof: count then list share one captured Instant but no outer TX; one distinct counterpart batch and per-row conditional blocks retain original phase order',
        'scope: selected list controller omits detail/static stream and other routes; it cannot prove full original routing, Gateway or SSE behavior',
        'scope: compiled favorites/page parameter names and query defaults require actual reflection/representative original-only observation before comparison; no copied full query matrix',
    ))
    pending = [(output / relative, content) for relative, content in generated.items()]
    for relative, content in generated.items():
        entries.append(sha256(content) + '  generated ' + relative)
    receipt = ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode('utf-8')
    pending.append((evidence, receipt))
    # No filesystem mutation occurs before every source/shared/unit/output/receipt check succeeds.
    for destination, expected in pending:
        require_plain_path(destination, 'oracle output or evidence')
        if destination.exists() and (not destination.is_file() or destination.read_bytes() != expected):
            raise SystemExit('Refusing to overwrite differing F23 oracle source or evidence')
    for destination, expected in pending:
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
    print('Verified F23 pinned sources/shared wiring; wrote three selected test-only wrappers and evidence')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F23 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
