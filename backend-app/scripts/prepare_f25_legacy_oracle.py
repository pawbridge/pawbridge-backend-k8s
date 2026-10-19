#!/usr/bin/env python3
"""Pin F25 selected-original block wrappers; reuse prior shared FQNs without overwriting.

Private preparation only until separately executed. Extraction starts no original
whole app, HTTP/Feign transport, JVM, database, Stream/SSE or scheduler.
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
  'signature': '    public void block(long currentUserId, long blockedUserId) {',
  'start_anchor': '    public void block(long currentUserId, long blockedUserId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '8e34e4f2353d3f85245de602f215f2bfa7aa8861ea86a69ee81c566f3e0fe9a7'},
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
  'signature': '    private static void requireId(long userId) {',
  'start_anchor': '    private static void requireId(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'start_anchor': '    private static ResponseStatusException error(HttpStatus status, String message) '
                  '{',
  'end_delimiter': '\n    }\n',
  'sha256': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad'}]

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

CONTROLLER_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
 'signature': '    public void block(',
 'start_anchor': '    @PutMapping("/blocks/{member}")\n',
 'end_delimiter': '\n    }\n',
 'sha256': '40bd4b2e8433eba171ef806b7f1c202a644d20d95e7a525528f49b94b442efa5'}

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
  'signature': '    public void lockMembers(long firstMemberId, long secondMemberId) {',
  'start_anchor': '    /** Uses the same member lock order for sending, blocking and withdrawal. */\n',
  'end_delimiter': '\n    }\n',
  'sha256': '61691e2a9c58387e66ff7761d6bbe00e858ccbd01343de5a9169e9ab04ebbe3a'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public void block(long ownerId, long blockedUserId, Instant createdAt) {',
  'start_anchor': '    public void block(long ownerId, long blockedUserId, Instant createdAt) {',
  'end_delimiter': '\n    }\n',
  'sha256': '1acb6bab32bf8261928203ea845e4eac19b22b0840dffd2a2f908d0e15ca655d'}]

MODEL_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record ContactMember(Long userId, String nickname, boolean active, '
                   'boolean deletionPending) {}\n',
  'sha256': 'c037d57bad5a40e651d625333788ff4267b5803b84825fdfc006943e885a6448'}]

DDL_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_members (',
  'end_delimiter': ');\n',
  'sha256': '3013f85e076821cc380c7d8e3c4f750869a053b80deff0938613fd684d0c913e'},
 {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
  'start_anchor': 'CREATE TABLE private_note_blocks (',
  'end_delimiter': ');\n',
  'sha256': '02b79fe553ca9307b80422a9804652850914fd75b55516616dffaccd159bf9c7'}]

INDEX_UNITS = [{'source': 'community-service/src/migration/resources/db/postgresql/V8__private_note_reference_indexes.sql',
  'verbatim_utf8': 'CREATE INDEX idx_private_note_blocked_member ON private_note_blocks (blocked_id);\n',
  'sha256': 'fa1e45bc305da331c2e478cd17ada3083ab3856bee7648df3f2ac2befe9576d4'}]

F18_ADAPTED_PATH = 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

F18_ADAPTED_SHA256 = 'fc02a8aefbe87a5f9b3e7d3017334d0b38d1fb7fe5b499cc080528e6cc1a03bf'

F18_ADAPTED_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n')

OUTPUT_ROOT = 'backend-app/boot/build/generated/sources/f25LegacyOracle/java'

EVIDENCE_PATH = 'backend-app/build/ci/f25-oracle-sha256.txt'

OUTPUTS = ('com/pawbridge/communityservice/contact/F25LegacyNoteBlockCreateService.java',
 'com/pawbridge/communityservice/contact/F25LegacyNoteBlockCreateController.java',
 'com/pawbridge/communityservice/contact/F25LegacyNoteBlockCreateExceptionAdvice.java')

ORIGINAL_CLOCK_FIELD = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private final Clock clock;\n',
 'sha256': 'a7bcbb30ed951787b3ea090bbeee27f2d5395b6a9fb0d8f2a293fb9a332d49ce'}

ORIGINAL_CLOCK_ASSIGNMENT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '        this.clock = clock;\n',
 'sha256': '3a52b7823bda5b28ef3fed4844f45438d1e1c1782027e70267dd45b242860599'}

F25_ADVICE_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F25LegacyNoteBlockCreateController.class)\n')

def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_method(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F25 selected method/annotation is not unique: ' + signature.strip())
    begin = text.index(anchor)
    try:
        signature_at = text.index(signature, begin)
        finish = text.index(ending, signature_at) + len(ending)
    except ValueError:
        raise SystemExit('F25 selected method boundary differs: ' + signature.strip()) from None
    selected = text[begin:finish]
    if not selected.endswith('}\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F25 selected method/LF checksum mismatch: ' + signature.strip())
    return selected


def checked_section(text, specification):
    begin, ending = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(ending) != 1:
        raise SystemExit('F25 selected class annotation boundaries are not unique')
    first, last = text.index(begin), text.index(ending)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F25 selected class annotation/LF checksum mismatch')
    return selected


def checked_literal(text, specification):
    selected = specification['verbatim_utf8']
    if (text.count(selected) != 1 or not selected.endswith('\n')
            or sha256(selected.encode('utf-8')) != specification['sha256']):
        raise SystemExit('F25 selected constant/model/logger literal or LF differs')
    return selected


def checked_fragment(text, specification):
    anchor, ending = specification['start_anchor'], specification['end_delimiter']
    if text.count(anchor) != 1:
        raise SystemExit('F25 source-only DDL fragment is not unique')
    first = text.index(anchor)
    try:
        last = text.index(ending, first) + len(ending)
    except ValueError:
        raise SystemExit('F25 source-only DDL fragment boundary differs') from None
    selected = text[first:last]
    if not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F25 source-only DDL fragment/LF checksum mismatch')
    return selected


def require_plain_path(path, label):
    if path.is_symlink() or path.resolve() != path:
        raise SystemExit('Refusing redirected F25 ' + label)
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise SystemExit('Refusing non-directory parent of F25 ' + label)


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
                    raise SystemExit('Refusing duplicate F25 wrapper FQN: ' + wanted_package + '.' + name)


def extract(repository, output, evidence):
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(value).absolute() for value in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / EVIDENCE_PATH:
        raise SystemExit('Refusing non-dedicated F25 oracle output')
    require_plain_path(output, 'oracle output')
    require_plain_path(evidence, 'oracle evidence')

    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if sha256(content) != expected:
            raise SystemExit('Legacy F25 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  source ' + source)
    for source, relative in SHARED.items():
        target = repository / relative
        require_plain_path(target, 'shared source')
        if not target.is_file() or target.read_bytes() != inputs[source]:
            raise SystemExit('Shared generated bytes differ from fixed F25 source: ' + source)
        entries.append(EXPECTED[source] + '  shared-byte-identity ' + relative + ' <= ' + source)

    texts = {source: content.decode('utf-8') for source, content in inputs.items()}
    service_methods = [checked_method(texts[SERVICE], spec) for spec in SERVICE_UNITS]
    checked_method(texts[SERVICE], SERVICE_CONSTRUCTOR_UNIT)
    checked_literal(texts[SERVICE], ORIGINAL_TRANSACTION_FIELD)
    checked_literal(texts[SERVICE], ORIGINAL_CLOCK_FIELD)
    checked_literal(texts[SERVICE], ORIGINAL_CLOCK_ASSIGNMENT)
    checked_literal(texts[SERVICE], TRANSACTION_CONFIGURATION)
    controller_annotations = checked_section(texts[CONTROLLER], CONTROLLER_ANNOTATIONS)
    controller_method = checked_method(texts[CONTROLLER], CONTROLLER_UNIT)
    advice_annotations = checked_section(texts[ADVICE], ADVICE_ANNOTATIONS)
    advice_methods = [checked_method(texts[ADVICE], spec) for spec in ADVICE_UNITS]
    logger = checked_literal(texts[ADVICE], LOGGER)
    for spec in SOURCE_EVIDENCE_UNITS:
        checked_method(texts[spec['source']], spec)
    for spec in MODEL_UNITS + INDEX_UNITS:
        checked_literal(texts[spec['source']], spec)
    for spec in DDL_UNITS:
        checked_fragment(texts[spec['source']], spec)

    adapted_path = repository / F18_ADAPTED_PATH
    require_plain_path(adapted_path, 'adapted shared advice')
    if not adapted_path.is_file():
        raise SystemExit('F25 requires existing F18 original-name adapted advice')
    adapted = adapted_path.read_bytes()
    expected_adapted = texts[ADVICE].replace(advice_annotations, F18_ADAPTED_ANNOTATIONS, 1).encode('utf-8')
    if sha256(adapted) != F18_ADAPTED_SHA256 or adapted != expected_adapted:
        raise SystemExit('F18 adapted advice differs from the fixed scope-only F25 reference')
    if adapted.decode('utf-8').count(logger) != 1:
        raise SystemExit('F18 original-name logger wiring differs from F25 reference')
    entries.append(F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + F18_ADAPTED_PATH)

    fields = ('    private final PrivateNoteRepository privateNoteRepository;\n',
              '    private final UserServiceClient userServiceClient;\n',
              ORIGINAL_CLOCK_FIELD['verbatim_utf8'])
    if any(texts[SERVICE].count(field) != 1 for field in fields):
        raise SystemExit('F25 original service injection fields differ')
    if texts[CONTROLLER].count('    private final PrivateNoteService privateNoteService;\n') != 1:
        raise SystemExit('F25 original controller injection field differs')
    declaration = 'public class PrivateNoteExceptionAdvice {'
    if texts[ADVICE].count(declaration) != 1:
        raise SystemExit('F25 original advice declaration differs')
    service = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import feign.FeignException;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;
/** Six verbatim original methods; caller supplies the original-manager TX15 operations. Clock supplied; no Stream/events. */
public final class F25LegacyNoteBlockCreateService {
''' + ''.join(fields) + '''    private final TransactionOperations transactions;
    public F25LegacyNoteBlockCreateService(PrivateNoteRepository repository, UserServiceClient client,
                                            TransactionOperations transactions, Clock clock) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
        this.transactions = transactions;
        this.clock = clock;
    }
''' + '\n'.join(service_methods) + '}\n'
    controller = '''package com.pawbridge.communityservice.contact;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
''' + controller_annotations + '''public final class F25LegacyNoteBlockCreateController {
    private final F25LegacyNoteBlockCreateService privateNoteService;
    public F25LegacyNoteBlockCreateController(F25LegacyNoteBlockCreateService service) {
        this.privateNoteService = service;
    }
''' + controller_method + '}\n'
    advice = texts[ADVICE].replace(advice_annotations, F25_ADVICE_ANNOTATIONS, 1).replace(
        declaration, 'public class F25LegacyNoteBlockCreateExceptionAdvice {', 1)
    if advice.count(logger) != 1 or any(method not in advice for method in advice_methods):
        raise SystemExit('F25 advice adaptation changed handler or original-name logger literal')
    if any(service.count(method) != 1 for method in service_methods) or controller.count(controller_method) != 1:
        raise SystemExit('F25 wrapper did not retain each selected original method including LF')
    generated = dict(zip(OUTPUTS, (value.encode('utf-8') for value in (service, controller, advice))))
    reject_duplicate_fqns(repository, output)

    for spec in (*SERVICE_UNITS, CONTROLLER_UNIT, *ADVICE_UNITS):
        entries.append(spec['sha256'] + '  wrapper-method ' + spec['signature'].strip())
    for spec in (SERVICE_CONSTRUCTOR_UNIT, *SOURCE_EVIDENCE_UNITS):
        entries.append(spec['sha256'] + '  source-evidence-method ' + spec['signature'].strip())
    for label, spec in (('original-transaction-field', ORIGINAL_TRANSACTION_FIELD),
                        ('original-clock-field', ORIGINAL_CLOCK_FIELD),
                        ('original-clock-assignment', ORIGINAL_CLOCK_ASSIGNMENT),
                        ('original-transaction-template-timeout15', TRANSACTION_CONFIGURATION),
                        ('controller-class-annotations', CONTROLLER_ANNOTATIONS),
                        ('original-advice-class-annotations', ADVICE_ANNOTATIONS),
                        ('original-advice-logger', LOGGER), ('contact-member-record', MODEL_UNITS[0])):
        entries.append(spec['sha256'] + '  selected-unit ' + label)
    for spec in DDL_UNITS:
        entries.append(spec['sha256'] + '  source-only-ddl-fragment ' + spec['start_anchor'])
    for spec in INDEX_UNITS:
        entries.append(spec['sha256'] + '  source-only-index ' + spec['verbatim_utf8'].strip())
    entries.extend((
        'wiring: unique F25 service/controller/advice only; no prior raw or adapted source is copied or overwritten',
        'wiring: six service methods plus annotated PUT block and four advice handlers retain original UTF-8 bytes and closing LF',
        'wiring: original Clock field and constructor assignment are pinned; the test constructor injects the explicit Clock without changing the verbatim block clock.instant call',
        'wiring: original TransactionTemplate field becomes TransactionOperations solely for explicit test injection; caller must supply TX15 operations',
        'proof: original full constructor and TransactionTemplate timeout15 are source-only evidence; this wrapper does not enforce injected operations timeout or manager type',
        'proof: original PG reference must use actual Hibernate JpaTransactionManager auto-configuration and timeout15; RecordingTM MVC cannot prove it',
        'wiring: controller class/constructor/injected service type change only; class mapping and explicit PathVariable member remain original',
        'wiring: advice class/scope alone adapt to F25; Order/imports/four handlers/logger literal remain original',
        'wiring: unchanged original-name logger literal resolves to existing F18 adapted advice FQN; do not register that advice bean or broaden its scope',
        'proof: original DAO maps to storage503; CannotCreateTransactionException/TransactionSystemException/TransactionTimedOutException and other Runtime map to generic503 by actual type; no F18 read-side TX reclassification',
        'proof: phase alone does not classify a failure; a DAO translated by the actual manager at commit still reaches original storage503',
        'proof: User Feign failure maps through original lookup to member503; candidate typed User failures need separate MVC/PG evidence',
        'proof: full Repository lockMembers/block and User get/getAll/validate run only when a harness actually calls shared classes; no copied SQL oracle',
        'scope: source-only V6 member/block and V8 index evidence executes no migration or ACL statement',
        'scope: extraction starts no JVM/PG/HTTP/Feign transport/app/scheduler/Stream/SSE; separate original-only observations and runtime proofs required',
        'scope: selected block validates both IDs then rejects self before transaction, then locks/current active/target active/Clock.instant once/outgoing INSERT ON CONFLICT DO NOTHING',
        'scope: inactive current stops target and Clock; inactive target stops Clock; repeat still checks both members and samples Clock but preserves stored created_at',
        'scope: Clock is supplied explicitly; no Stream/afterCommit/event/outbox/full lifecycle or original whole service execution is included',
    ))
    pending = [(output / relative, value) for relative, value in generated.items()]
    for relative, value in generated.items():
        entries.append(sha256(value) + '  generated ' + relative)
    receipt = ('source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n').encode('utf-8')
    pending.append((evidence, receipt))
    # Every source/shared/unit/FQN/output/receipt conflict is checked before the first filesystem mutation.
    for destination, expected in pending:
        require_plain_path(destination, 'oracle output or evidence')
        if destination.exists() and (not destination.is_file() or destination.read_bytes() != expected):
            raise SystemExit('Refusing to overwrite differing F25 oracle source or evidence')
    for destination, expected in pending:
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
    print('Verified pinned F25 sources/shared wiring; wrote three selected test-only wrappers and evidence')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F25 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
