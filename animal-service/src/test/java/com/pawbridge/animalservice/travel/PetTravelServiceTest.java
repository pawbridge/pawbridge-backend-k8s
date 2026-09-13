package com.pawbridge.animalservice.travel;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PetTravelServiceTest {
    private final PetTravelCatalog catalog=mock(PetTravelCatalog.class);
    private final PetTravelService service=new PetTravelService(catalog);
    private final Instant now=Instant.parse("2026-09-12T00:00:00Z");
    private Map<String,String> row() { return Map.of("contentid","123","title","공원","addr1","서울"); }
    private void detail(Map<String,String> common,Map<String,String> pet) {
        when(catalog.detail("123")).thenReturn(Optional.of(new PetTravelCatalog.Place(common,pet,now)));
    }
    @Test void givenSecondPage__whenRead__thenReturnStoredPageAndTotal() {
        when(catalog.regions()).thenReturn(List.of(new PetTravelCatalog.Region("11","서울",now,now)));
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("HIDDEN",1,null,now));
        when(catalog.countPlaces("11")).thenReturn(21L);
        when(catalog.places("11",1)).thenReturn(List.of(new PetTravelCatalog.Place(row(),Map.of(),now)));
        var response=service.places("11",1);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.items()).extracting(PetTravelResponse.Place::contentId).containsExactly("123");
    }
    @ParameterizedTest @ValueSource(ints={3,Integer.MAX_VALUE})
    void givenPageBeyondInventory__whenRead__thenEmptyPageRetainsPartialState(int page) {
        when(catalog.regions()).thenReturn(List.of(new PetTravelCatalog.Region("11","서울",now,null)));
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("SHOWN",1,null,null));
        when(catalog.countPlaces("11")).thenReturn(21L);
        var response=service.places("11",page);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.availability()).isEqualTo("PARTIAL");
        verify(catalog,never()).places(anyString(),anyInt());
    }
    @Test void givenNegativePage__whenRead__thenRejectBeforeDatabase() {
        assertThatThrownBy(()->service.places("11",-1)).isInstanceOf(PetTravelException.class)
                .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST);
        verifyNoInteractions(catalog);
    }
    @Test void givenStoredData__whenRead__thenNoKeyOrRedisDependencies() {
        when(catalog.regions()).thenReturn(List.of(new PetTravelCatalog.Region("11","서울",now,now)));
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("HIDDEN",1,null,now));
        when(catalog.countPlaces("11")).thenReturn(1L);
        when(catalog.places("11",0)).thenReturn(List.of(new PetTravelCatalog.Place(row(),Map.of(),now)));
        assertThat(service.places("11").items()).containsExactly(new PetTravelResponse.Place("123","공원","서울",null));
        assertThat(service.places("11").availability()).isEqualTo("READY");
        detail(row(),Map.of("acmpyPsblCpam","시각 장애인 안내견","acmpyNeedMtr","목줄"));
        assertThat(service.detail("123").conditions().allowedAnimals()).isEqualTo("시각 장애인 안내견");
        assertThat(service.detail("123").petInformationAvailable()).isTrue();
    }
    @ParameterizedTest @CsvSource({"false,false,PREPARING","false,true,FAILED","true,false,READY","true,true,STALE"})
    void givenCollectionState__whenRead__thenDistinguishAvailability(boolean completed,boolean failed,String expected) {
        when(catalog.regions()).thenReturn(List.of(new PetTravelCatalog.Region("11","서울",now,completed?now:null)));
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("DETAILS",1,failed?"COLLECTION_FAILED":null,completed?now:null));
        assertThat(service.places("11").availability()).isEqualTo(expected);
    }
    @Test void givenNoRegions__whenRead__thenEmptyPreparationResponse() {
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("HIDDEN",1,null,null));
        assertThat(service.regions().items()).isEmpty();
        assertThat(service.regions().fetchedAt()).isNull();
    }
    @Test void givenInitialCollectionFailure__whenRead__thenFailedNotEmptyRegion() {
        when(catalog.collectionState()).thenReturn(new PetTravelCatalog.CollectionState("HIDDEN",1,"COLLECTION_FAILED",null));
        assertThat(service.regions().availability()).isEqualTo("FAILED");
    }
    @Test void givenUnknownRegion__whenRead__thenBadRequest() {
        assertThatThrownBy(()->service.places("99")).isInstanceOf(PetTravelException.class).extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST);
        verify(catalog,never()).places(anyString());
    }
    @Test void givenMalformedId__whenRead__thenNoDatabaseWork() {
        assertThatThrownBy(()->service.detail("../x")).isInstanceOf(PetTravelException.class);
        verifyNoInteractions(catalog);
    }
    @Test void givenHiddenOrMissingPlace__whenRead__thenNotFound() {
        assertThatThrownBy(()->service.detail("123")).isInstanceOf(PetTravelException.class).extracting("code").isEqualTo(PetTravelException.Code.NOT_FOUND);
    }
    @Test void givenEmptyPetInformation__whenRead__thenUnknownNotAllowed() {
        detail(row(),Map.of());
        assertThat(service.detail("123").petInformationAvailable()).isFalse();
        assertThat(service.detail("123").conditions().allowedAnimals()).isNull();
        assertThat(service.detail("123").petInformationStatus()).isEqualTo("READY");
        assertThat(service.detail("123").petInformationFetchedAt()).isEqualTo(now);
    }
    @Test void givenBasicDataOnly__whenDetailRead__thenPendingWithoutInventedFetchTime() {
        when(catalog.detail("123")).thenReturn(Optional.of(new PetTravelCatalog.Place(row(),Map.of(),null,now,"PREPARING")));
        var response=service.detail("123");
        assertThat(response.place().title()).isEqualTo("공원");
        assertThat(response.petInformationStatus()).isEqualTo("PREPARING");
        assertThat(response.petInformationFetchedAt()).isNull();
        assertThat(response.fetchedAt()).isEqualTo(now);
        assertThat(response.petInformationAvailable()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"Type1","Type3"})
    void givenPermittedPhoto__whenRead__thenHttpsOriginal(String type) {
        var data=new HashMap<>(row());
        data.put("cpyrhtDivCd",type);data.put("firstimage","http://tong.visitkorea.or.kr/cms/resource/1/test.jpg");
        detail(data,Map.of());
        assertThat(service.detail("123").place().imageUrl()).isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1/test.jpg");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"Type2","Type4","unknown"})
    void givenUnpermittedPhotoType__whenRead__thenOmitPhoto(String type) {
        var data=new HashMap<>(row());if(type!=null)data.put("cpyrhtDivCd",type);
        data.put("firstimage","https://tong.visitkorea.or.kr/cms/resource/1/test.jpg");
        detail(data,Map.of());assertThat(service.detail("123").place().imageUrl()).isNull();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"javascript:alert(1)","https://example.com/a.jpg",
        "https://tong.visitkorea.or.kr.evil.example/cms/resource/1/a.jpg","https://user@tong.visitkorea.or.kr/cms/resource/1/a.jpg",
        "https://tong.visitkorea.or.kr:8443/cms/resource/1/a.jpg","https://tong.visitkorea.or.kr/cms/resource/1/a.jpg?token=x",
        "https://tong.visitkorea.or.kr/cms/resource/1/a.jpg#fragment","https://tong.visitkorea.or.kr/cms/resource/../a.jpg"})
    void givenUnsafePhoto__whenRead__thenOmitPhoto(String image) {
        var data=new HashMap<>(row());data.put("cpyrhtDivCd","Type1");if(image!=null)data.put("firstimage",image);
        detail(data,Map.of());assertThat(service.detail("123").place().imageUrl()).isNull();
    }
}
