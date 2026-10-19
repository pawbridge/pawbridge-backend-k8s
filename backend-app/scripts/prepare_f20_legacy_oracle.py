#!/usr/bin/env python3
"""Pin F20 block-list sources and generate only three uniquely named test wrappers.

This tool does not run a legacy service, HTTP/Feign transport, database or JVM.
Shared raw source bytes and the F18 scope-adapted advice are read-only inputs.
"""
import hashlib
import pathlib
import re
import subprocess
import sys

SOURCE = '2b160c07bce84dc47f2d2d3a7f440f560b3f870f'

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
 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql': '3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119'}

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
  'signature': '    public BlockPage blocks(long currentUserId, int page) {',
  'start_anchor': '    public BlockPage blocks(long currentUserId, int page) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'c560b5a4db245cf2bf060ca16aa4df0022e73be5ff9741772537e88003fd46d4'},
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
  'signature': '    private static int totalPages(long count) {',
  'start_anchor': '    private static int totalPages(long count) {',
  'end_delimiter': '\n    }\n',
  'sha256': '9c2ff38acf26cef059215d29d9975b5d84ac93f6c98c49d878e8432000557363'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static void requirePage(int page) {',
  'start_anchor': '    private static void requirePage(int page) {',
  'end_delimiter': '\n    }\n',
  'sha256': '0ee3fa29b4942ce4159d290273e492350bb67ce7c552f404302da6f9682d6f05'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static void requireId(long userId) {',
  'start_anchor': '    private static void requireId(long userId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '18d4ee2172744a8cfb5711f2d4b21fbc9c8e5052ad14f6be91f0b2866603f031'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException memberLookupUnavailable() {',
  'start_anchor': '    private static ResponseStatusException memberLookupUnavailable() {',
  'end_delimiter': '\n    }\n',
  'sha256': 'd05eecaca914fd6b267439710d57fc313e6c77d303fd64f1b3589b64de12aecc'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
  'signature': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'start_anchor': '    private static ResponseStatusException error(HttpStatus status, String message) {',
  'end_delimiter': '\n    }\n',
  'sha256': '6a0d213030b2d6481724b2a74ebf6b3c4ea1d40a80be03e927efe77b416d4dad'}]

PAGE_SIZE = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteService.java',
 'verbatim_utf8': '    private static final int NOTE_PAGE_SIZE = 10;\n',
 'sha256': 'b1b85fa04e4233bb974026a296eddcaeb203c8f4e1e40f1e55ca18a7632fc44a'}

CONTROLLER_UNIT = {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteController.java',
 'signature': '    public ResponseDTO<BlockPage> blocks(',
 'start_anchor': '    @GetMapping("/blocks")\n',
 'end_delimiter': '\n    }\n',
 'sha256': '8c95bca8c7fa30d4991fd09a98d467854f12e0255c84499391abe5cf5a14abce'}

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
  'signature': '    public List<ContactMember> getAll(List<Long> userIds) {',
  'start_anchor': '    public List<ContactMember> getAll(List<Long> userIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': '1a88751e48f0e2b5bd3a85177984ab57cdb6b4a546e2fb0d762f08b0391abe73'},
 {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
  'signature': '    private static void validateIds(List<Long> userIds) {',
  'start_anchor': '    private static void validateIds(List<Long> userIds) {',
  'end_delimiter': '\n    }\n',
  'sha256': 'f2ae79e5c62e4e13d4e87b673d12b68be14e07ec7501132996055d3fb7c42c56'},
 {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberController.java',
  'signature': '    public List<ContactMember> getAll(@RequestParam("ids") List<Long> userIds) {',
  'start_anchor': '    @GetMapping("/contacts")\n',
  'end_delimiter': '\n    }\n',
  'sha256': 'de994ade4c6328b0e51084c8808a77a2e2cac5759165eb43a9a580d49d9c99a2'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public List<Block> blocks(long ownerId, int page) {',
  'start_anchor': '    public List<Block> blocks(long ownerId, int page) {',
  'end_delimiter': '\n    }\n',
  'sha256': '0e378297c885ac62471ecbbb898d8c53ad58a227ec2909f7536afdf18473a5a3'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteRepository.java',
  'signature': '    public long blockCount(long ownerId) {',
  'start_anchor': '    public long blockCount(long ownerId) {',
  'end_delimiter': '\n    }\n',
  'sha256': '3d3569ea019cd007eec24240fd2563ebe3dc31910a6cc40740b16d0460f4feb5'}]

USER_LIMIT = {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberQuery.java',
 'verbatim_utf8': '    private static final int MAX_MEMBERS_PER_REQUEST = 21;\n',
 'sha256': '7e531652b929cfdfb54fce449c653e9cddf68d71c217d8f8680cfc4c0416f026'}

USER_CONTROLLER_ANNOTATIONS = {'source': 'user-service/src/main/java/com/pawbridge/userservice/contact/ContactMemberController.java',
 'start_anchor': '@RestController\n',
 'end_exclusive_anchor': 'public class ContactMemberController {',
 'sha256': '974cf4b27a25f82b86f9297b7c8d6e8a9ec0149b00de4ffc057f9cfad15e1cd1'}

MODEL_UNITS = [{'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record BlockView(Long memberId, String nickname, Instant createdAt) {}\n',
  'sha256': '3c7165446e047cf1d1f2e8a2536abc7ec17c9395e2dc3996f6226256e97b3a73'},
 {'source': 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteModels.java',
  'verbatim_utf8': '    public record BlockPage(List<BlockView> content, long totalElements, int totalPages, '
                   'int number) {}\n',
  'sha256': 'e12c1d2fd6127b47d0c9cfecb7c63d46b5d67022ef24ee2b642f545956f4b05d'}]

V6_BLOCKS = {'source': 'community-service/src/migration/resources/db/postgresql/V6__private_notes.sql',
 'start_anchor': 'CREATE TABLE private_note_blocks (',
 'end_delimiter': ');\n',
 'sha256': '02b79fe553ca9307b80422a9804652850914fd75b55516616dffaccd159bf9c7'}

F18_ADAPTED_PATH = 'backend-app/boot/build/generated/sources/f18LegacyOracle/java/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.java'

F18_ADAPTED_SHA256 = 'fc02a8aefbe87a5f9b3e7d3017334d0b38d1fb7fe5b499cc080528e6cc1a03bf'

F18_ADAPTED_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)\n')

F20_ADVICE_ANNOTATIONS = ('@Order(Ordered.HIGHEST_PRECEDENCE)\n'
 '@RestControllerAdvice(assignableTypes = F20LegacyBlockListController.class)\n')

OUTPUT_ROOT = 'backend-app/boot/build/generated/sources/f20LegacyOracle/java'

EVIDENCE_PATH = 'backend-app/build/ci/f20-oracle-sha256.txt'

OUTPUTS = ('com/pawbridge/communityservice/contact/F20LegacyBlockListService.java',
 'com/pawbridge/communityservice/contact/F20LegacyBlockListController.java',
 'com/pawbridge/communityservice/contact/F20LegacyBlockListExceptionAdvice.java')


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def checked_method(text, specification):
    anchor, signature, ending = (specification[key] for key in ('start_anchor', 'signature', 'end_delimiter'))
    if text.count(anchor) != 1 or text.count(signature) != 1:
        raise SystemExit('F20 selected method/annotation is not unique: ' + signature.strip())
    begin = text.index(anchor)
    try:
        signature_at = text.index(signature, begin)
        finish = text.index(ending, signature_at) + len(ending)
    except ValueError:
        raise SystemExit('F20 selected method boundary differs: ' + signature.strip()) from None
    selected = text[begin:finish]
    if not selected.endswith('}\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F20 selected method/LF checksum mismatch: ' + signature.strip())
    return selected


def checked_section(text, specification):
    begin, ending = specification['start_anchor'], specification['end_exclusive_anchor']
    if text.count(begin) != 1 or text.count(ending) != 1:
        raise SystemExit('F20 selected class annotation boundaries are not unique')
    first, last = text.index(begin), text.index(ending)
    selected = text[first:last]
    if last <= first or not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F20 selected class annotation/LF checksum mismatch')
    return selected


def checked_literal(text, specification):
    selected = specification['verbatim_utf8']
    if (text.count(selected) != 1 or not selected.endswith('\n')
            or sha256(selected.encode('utf-8')) != specification['sha256']):
        raise SystemExit('F20 selected constant/model/logger literal or LF differs')
    return selected


def checked_fragment(text, specification):
    anchor, ending = specification['start_anchor'], specification['end_delimiter']
    if text.count(anchor) != 1:
        raise SystemExit('F20 source-only V6 block fragment is not unique')
    first = text.index(anchor)
    try:
        last = text.index(ending, first) + len(ending)
    except ValueError:
        raise SystemExit('F20 source-only V6 block fragment boundary differs') from None
    selected = text[first:last]
    if not selected.endswith('\n') or sha256(selected.encode('utf-8')) != specification['sha256']:
        raise SystemExit('F20 source-only V6 block fragment/LF checksum mismatch')
    return selected


def require_plain_path(path, label):
    if path.is_symlink() or path.resolve() != path:
        raise SystemExit('Refusing redirected F20 ' + label)
    if any(parent.exists() and not parent.is_dir() for parent in path.parents):
        raise SystemExit('Refusing non-directory parent of F20 ' + label)


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
                    raise SystemExit('Refusing duplicate F20 wrapper FQN: ' + wanted_package + '.' + name)


def extract(repository, output, evidence):
    repository = pathlib.Path(repository).resolve()
    output, evidence = (pathlib.Path(value).absolute() for value in (output, evidence))
    if output != repository / OUTPUT_ROOT or evidence != repository / EVIDENCE_PATH:
        raise SystemExit('Refusing non-dedicated F20 oracle output')
    # Validate destination redirection before Git reads and before any mkdir/write.
    require_plain_path(output, 'oracle output')
    require_plain_path(evidence, 'oracle evidence')

    inputs, entries = {}, []
    for source, expected in EXPECTED.items():
        content = subprocess.check_output(['git', 'show', SOURCE + ':' + source], cwd=repository)
        if sha256(content) != expected:
            raise SystemExit('Legacy F20 input checksum mismatch: ' + source)
        inputs[source] = content
        entries.append(expected + '  source ' + source)
    for source, relative in SHARED.items():
        generated = repository / relative
        require_plain_path(generated, 'shared source')
        if not generated.is_file() or generated.read_bytes() != inputs[source]:
            raise SystemExit('Shared generated bytes differ from fixed F20 source: ' + source)
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
    checked_literal(text[USER_LIMIT['source']], USER_LIMIT)
    checked_section(text[USER_CONTROLLER_ANNOTATIONS['source']], USER_CONTROLLER_ANNOTATIONS)
    for specification in MODEL_UNITS:
        checked_literal(text[specification['source']], specification)
    checked_fragment(text[V6_BLOCKS['source']], V6_BLOCKS)

    adapted_path = repository / F18_ADAPTED_PATH
    require_plain_path(adapted_path, 'adapted shared advice')
    if not adapted_path.is_file():
        raise SystemExit('F20 requires the existing F18 original-name adapted advice')
    adapted = adapted_path.read_bytes()
    expected_adapted = text[ADVICE].replace(advice_annotations, F18_ADAPTED_ANNOTATIONS, 1).encode('utf-8')
    if sha256(adapted) != F18_ADAPTED_SHA256 or adapted != expected_adapted:
        raise SystemExit('F18 adapted advice differs from the fixed scope-only F20 reference')
    if adapted.decode('utf-8').count(logger) != 1:
        raise SystemExit('F18 original-name logger wiring differs from the F20 reference')
    entries.append(F18_ADAPTED_SHA256 + '  shared-adapted-identity ' + F18_ADAPTED_PATH)

    fields = ('    private final PrivateNoteRepository privateNoteRepository;\n',
              '    private final UserServiceClient userServiceClient;\n')
    if any(text[SERVICE].count(field) != 1 for field in fields):
        raise SystemExit('F20 original service injection fields differ')
    if text[CONTROLLER].count('    private final PrivateNoteService privateNoteService;\n') != 1:
        raise SystemExit('F20 original controller injection field differs')
    original_declaration = 'public class PrivateNoteExceptionAdvice {'
    if text[ADVICE].count(original_declaration) != 1:
        raise SystemExit('F20 original advice class declaration differs')

    service_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockPage;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockView;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import feign.FeignException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Test-only wiring around twelve unchanged original methods; no original lifecycle or outer TX. */
public final class F20LegacyBlockListService {
''' + page_size + '\n' + ''.join(fields) + '''
    public F20LegacyBlockListService(PrivateNoteRepository repository, UserServiceClient client) {
        this.privateNoteRepository = repository;
        this.userServiceClient = client;
    }
''' + '\n'.join(service_methods) + '}\n'
    controller_wrapper = '''package com.pawbridge.communityservice.contact;
import com.pawbridge.communityservice.contact.PrivateNoteModels.BlockPage;
import com.pawbridge.communityservice.util.ResponseDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
''' + controller_annotations + '''public final class F20LegacyBlockListController {
    private final F20LegacyBlockListService privateNoteService;
    public F20LegacyBlockListController(F20LegacyBlockListService service) {
        this.privateNoteService = service;
    }
''' + controller_method + '}\n'
    advice_wrapper = text[ADVICE].replace(advice_annotations, F20_ADVICE_ANNOTATIONS, 1).replace(
        original_declaration, 'public class F20LegacyBlockListExceptionAdvice {', 1)
    # Preserve the original class literal: it now resolves to the reused F18 advice FQN.
    if advice_wrapper.count(logger) != 1 or any(method not in advice_wrapper for method in advice_methods):
        raise SystemExit('F20 advice adaptation changed a handler or the original-name logger literal')
    for method in service_methods:
        if service_wrapper.count(method) != 1:
            raise SystemExit('F20 wrapper did not retain an exact original service method')
    if controller_wrapper.count(controller_method) != 1:
        raise SystemExit('F20 wrapper did not retain the complete annotated controller method')
    generated = dict(zip(OUTPUTS, (content.encode('utf-8') for content in
                                 (service_wrapper, controller_wrapper, advice_wrapper))))
    reject_duplicate_fqns(repository, output)

    for specification in (*SERVICE_UNITS, CONTROLLER_UNIT, *ADVICE_UNITS):
        entries.append(specification['sha256'] + '  wrapper-method ' + specification['signature'].strip())
    for specification in SOURCE_EVIDENCE_UNITS:
        entries.append(specification['sha256'] + '  source-evidence-method ' + specification['signature'].strip())
    for label, specification in (('service-page-size', PAGE_SIZE), ('controller-class-annotations', CONTROLLER_ANNOTATIONS),
                                 ('original-advice-class-annotations', ADVICE_ANNOTATIONS), ('original-advice-logger', LOGGER),
                                 ('user-raw-batch-limit', USER_LIMIT), ('user-http-class-annotations', USER_CONTROLLER_ANNOTATIONS),
                                 ('block-view-record', MODEL_UNITS[0]), ('block-page-record', MODEL_UNITS[1]),
                                 ('source-only-v6-blocks-fragment', V6_BLOCKS)):
        entries.append(specification['sha256'] + '  selected-unit ' + label)
    entries.extend((
        'wiring: unique F20 service/controller/advice classes; service retains only two original fields and an explicit constructor',
        'wiring: twelve service methods and NOTE_PAGE_SIZE=10 are verbatim including closing LF; no outer transaction or lifecycle fields',
        'wiring: original controller class mapping and annotated blocks method/default page remain verbatim; only injected service type/constructor change',
        'wiring: advice class name and assignableTypes alone adapt to F20; original imports, Order, four handlers and logger literal remain verbatim',
        'wiring: unchanged LoggerFactory.getLogger(PrivateNoteExceptionAdvice.class) resolves to the existing F18 original-name adapted class; logger category remains original FQN',
        'wiring: do not register the F18 advice bean for F20 or broaden its scope; retain its already-built classpath closure',
        'proof: twelve raw shared FQNs and one F18 adapted advice are read-only reused inputs; no Boundary/DTO/Models/Repository/User source is copied',
        'proof: original User getAll/validate and Repository count/list execute only when a separate harness calls the existing actual shared classes',
        'proof: internal User HTTP/getAll mapping and V6 TSTZ/PK/FK/CHECK are source evidence; no User HTTP, Feign transport or DDL is executed',
        'proof: service wrapper runs selected original methods only; no whole original PrivateNoteService, transaction proxy, app, SSE, chat or lifecycle execution',
        'scope: extraction starts no JVM/PG/HTTP/socket or scheduling; separate runtime/MVC/PG evidence is required',
        'scope: default page uses original implicit parameter name; compiled metadata and empty/repeated binding results require separate actual MVC observation',
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
            raise SystemExit('Refusing to overwrite differing F20 oracle source or evidence')
    for destination, expected in pending:
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(expected)
    print('Verified F20 pinned sources/shared wiring; wrote three selected test-only wrappers and evidence')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('F20 oracle requires repository, generated source output and evidence')
    extract(*sys.argv[1:])
